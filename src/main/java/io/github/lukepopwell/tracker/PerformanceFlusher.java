package io.github.lukepopwell.tracker;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Times requests in-process, bucketed by transaction name (see the servlet performance-tracking
 * filter), and periodically flushes each distinct bucket as one small aggregate report, rather
 * than one network call per request. Ported from
 * {@code gems/forge_ops_tracker/lib/forge_ops_tracker/performance_flusher.rb}; structurally the
 * same shape as {@link SessionFlusher} right next to it, just keyed by a map of buckets instead
 * of two scalar counters, for the same {@link ScheduledExecutorService}/shutdown-hook reasons
 * that class's own Javadoc documents.
 */
public class PerformanceFlusher {
  private final Configuration configuration;
  private final Client client;
  private final Object lock = new Object();
  private final Map<String, Bucket> buckets = new LinkedHashMap<>();
  private Instant periodStartedAt = Instant.now();
  private int failedFlushes;
  private volatile ScheduledExecutorService executor;
  private final AtomicBoolean shutdownHookRegistered = new AtomicBoolean();

  private static final class Bucket {
    long count;
    double durationSumMs;
    double maxDurationMs;
    final Map<String, Long> histogram = new LinkedHashMap<>();
  }

  public PerformanceFlusher(Configuration configuration, Client client) {
    this.configuration = configuration;
    this.client = client;
  }

  public void record(String transactionName, double durationMs) {
    ensureScheduled();
    // Under strict privacy a raw path (the fallback when no route matched) loses its ids.
    String name = configuration.isStrictPrivacy() ? StrictPrivacy.spanName(transactionName) : transactionName;
    synchronized (lock) {
      Bucket bucket = buckets.computeIfAbsent(name, key -> new Bucket());
      bucket.count++;
      bucket.durationSumMs += durationMs;
      bucket.maxDurationMs = Math.max(bucket.maxDurationMs, durationMs);
      // The distribution count/sum/max can't reconstruct: see HistogramBucketer for why the
      // server approximates a percentile from these bucket counts.
      bucket.histogram.merge(HistogramBucketer.bucketFor(durationMs), 1L, Long::sum);
    }
  }

  /**
   * Snapshots and resets the buffered buckets, then delivers them as one batch. A failed
   * delivery keeps every bucket where it is rather than resetting, so the next flush's batch
   * just grows instead of losing what was already tallied; same reasoning
   * {@link SessionFlusher#flush} already documents, with the same two limits: a batch the server
   * rejected is dropped at once, and so is one whose delivery has failed
   * {@link DeliveryResult#MAX_FAILED_FLUSHES} flushes in a row.
   *
   * <p>Only exactly what this snapshot delivered (or gave up on) is removed afterward, subtracted from whatever is
   * in each bucket by then, never the whole map cleared: {@link #record} can run on another thread
   * while delivery is in flight (the lock is released around the network call), so a record for a
   * transaction already in the snapshot, or a brand-new one, can land between the snapshot and
   * delivery succeeding, and clearing afterward would silently discard it. {@code maxDurationMs} is
   * left as whatever is currently on the bucket, sent or not: a max can't be "subtracted" back out,
   * and leaving it never overstates the next period's own max.
   */
  public void flush() {
    List<Map<String, Object>> samples;
    Map<String, Bucket> sent = new LinkedHashMap<>();
    Instant periodEndedAt;
    synchronized (lock) {
      if (buckets.isEmpty()) {
        return;
      }
      periodEndedAt = Instant.now();
      samples = new ArrayList<>();
      for (Map.Entry<String, Bucket> entry : buckets.entrySet()) {
        Bucket bucket = entry.getValue();
        Bucket copy = new Bucket();
        copy.count = bucket.count;
        copy.durationSumMs = bucket.durationSumMs;
        copy.histogram.putAll(bucket.histogram);
        sent.put(entry.getKey(), copy);
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("transaction_name", entry.getKey());
        sample.put("environment", configuration.getEnvironment());
        sample.put("release", configuration.getRelease());
        sample.put("period_started_at", periodStartedAt.toString());
        sample.put("period_ended_at", periodEndedAt.toString());
        sample.put("request_count", bucket.count);
        sample.put("duration_sum_ms", bucket.durationSumMs);
        sample.put("max_duration_ms", bucket.maxDurationMs);
        sample.put("histogram", new LinkedHashMap<>(bucket.histogram));
        samples.add(sample);
      }
    }

    DeliveryResult result = client.outcome(() -> client.deliverPerformanceSamples(samples));

    synchronized (lock) {
      if (result == DeliveryResult.FAILED && ++failedFlushes < DeliveryResult.MAX_FAILED_FLUSHES) {
        return;
      }
      failedFlushes = 0;
      for (Map.Entry<String, Bucket> delivered : sent.entrySet()) {
        Bucket current = buckets.get(delivered.getKey());
        if (current == null) {
          continue;
        }
        current.count -= delivered.getValue().count;
        current.durationSumMs = Math.max(0.0, current.durationSumMs - delivered.getValue().durationSumMs);
        for (Map.Entry<String, Long> label : delivered.getValue().histogram.entrySet()) {
          long remaining = current.histogram.getOrDefault(label.getKey(), 0L) - label.getValue();
          if (remaining > 0) {
            current.histogram.put(label.getKey(), remaining);
          } else {
            current.histogram.remove(label.getKey());
          }
        }
        if (current.count <= 0) {
          buckets.remove(delivered.getKey());
        }
      }
      periodStartedAt = periodEndedAt;
    }
    if (result != DeliveryResult.DELIVERED) {
      configuration.log("dropped performance samples for " + sent.size() + " transactions: " + result.dropReason());
    }
  }

  private void ensureScheduled() {
    if (executor != null) {
      return;
    }
    synchronized (this) {
      if (executor != null) {
        return;
      }
      ThreadFactory daemonThreadFactory = runnable -> {
        Thread thread = new Thread(runnable, "forge-ops-tracker-performance-flusher");
        thread.setDaemon(true);
        return thread;
      };
      ScheduledExecutorService scheduled = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory);
      long intervalSeconds = Math.max(1, configuration.getPerformanceFlushInterval().toSeconds());
      scheduled.scheduleWithFixedDelay(this::flushSafely, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
      executor = scheduled;
      registerShutdownHook();
    }
  }

  /** Same test-only purpose as {@link SessionFlusher#shutdown}. */
  void shutdown() {
    ScheduledExecutorService current = executor;
    if (current != null) {
      current.shutdownNow();
    }
  }

  private void flushSafely() {
    try {
      flush();
    } catch (RuntimeException e) {
      configuration.log("performance flush error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private void registerShutdownHook() {
    if (!shutdownHookRegistered.compareAndSet(false, true)) {
      return;
    }
    Thread shutdownThread = new Thread(this::flushSafely, "forge-ops-tracker-performance-flusher-shutdown");
    Runtime.getRuntime().addShutdownHook(shutdownThread);
  }
}
