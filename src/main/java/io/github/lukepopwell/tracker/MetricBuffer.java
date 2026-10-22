package io.github.lukepopwell.tracker;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Collects individual {@code captureMetric}/{@code captureInfrastructureMetric} calls in-process and
 * periodically flushes them as one batch, rather than one network call per capture. Unlike
 * {@link PerformanceFlusher} this keeps a <em>list</em> of individually meaningful entries instead
 * of summing them into buckets: a customer's own signup or payment is exactly the kind of thing they
 * will want a genuinely accurate count/sum of later, so the server stores one row per entry as-is.
 * Ported from {@code gems/forge_ops_tracker}'s {@code metric_buffer.rb} and
 * {@code infrastructure_metric_buffer.rb}, which are the same class twice; here it is one class
 * instantiated twice, told which delivery function and flush interval to use.
 *
 * <p>Three deliberate differences from the Ruby buffers:
 *
 * <ul>
 *   <li>A flush snapshots the first N entries and, on success, removes exactly those N, instead of
 *       resetting the whole list, so an entry recorded while the request is in flight (the lock is
 *       released around the network call) is kept for the next flush rather than lost.
 *   <li>The buffer is capped at {@link #MAX_ENTRIES}, and once full further entries are dropped
 *       until a flush empties it: an endpoint that is down fails every flush, and an uncapped
 *       buffer would then keep growing until the batch is given up on. Dropping the newest rather
 *       than the oldest keeps the entries a flush is delivering at the front of the list, which is
 *       what makes removing exactly them afterward exact.
 *   <li>A NaN or infinite value is dropped at record time: it is not valid JSON, and one bad entry
 *       would make the server reject the whole batch behind it.
 * </ul>
 *
 * <p>The same {@link ScheduledExecutorService} and JVM shutdown hook {@link PerformanceFlusher}
 * uses: the shutdown hook is what makes a short-lived cron program that captures a few readings and
 * returns from {@code main} still deliver them.
 */
public class MetricBuffer {
  public static final int MAX_ENTRIES = 1000;
  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

  private final Configuration configuration;
  private final Function<List<Map<String, Object>>, DeliveryResult> deliver;
  private final Supplier<Duration> interval;
  private final Object lock = new Object();
  private final List<Map<String, Object>> entries = new ArrayList<>();
  private volatile ScheduledExecutorService executor;
  private final AtomicBoolean shutdownHookRegistered = new AtomicBoolean();
  private int failedFlushes;

  /**
   * @param deliver takes a batch and returns whether delivery succeeded; {@code false} is a failure
   *     worth retrying
   * @param interval the flush interval, read once when the schedule starts
   */
  public MetricBuffer(Configuration configuration, Function<List<Map<String, Object>>, Boolean> deliver,
      Supplier<Duration> interval) {
    this(configuration, interval,
        batch -> Boolean.TRUE.equals(deliver.apply(batch)) ? DeliveryResult.DELIVERED : DeliveryResult.FAILED);
  }

  /**
   * The same buffer, for a {@code deliver} that can also say the server rejected the batch. The
   * arguments are in a different order only so a lambda can never be ambiguous between the two.
   */
  MetricBuffer(Configuration configuration, Supplier<Duration> interval,
      Function<List<Map<String, Object>>, DeliveryResult> deliver) {
    this.configuration = configuration;
    this.deliver = deliver;
    this.interval = interval;
  }

  /**
   * Adds one entry (everything but {@code recorded_at}, which is stamped here).
   *
   * @return whether it was kept
   */
  public boolean record(Map<String, Object> entry) {
    Object value = entry.get("value");
    if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
      configuration.log("dropped a metric with a non-finite value");
      return false;
    }

    ensureScheduled();
    synchronized (lock) {
      if (entries.size() >= MAX_ENTRIES) {
        configuration.log("metric buffer full, dropping a metric");
        return false;
      }
      Map<String, Object> stamped = new LinkedHashMap<>(entry);
      stamped.put("recorded_at", TIMESTAMP.format(Instant.now()));
      entries.add(stamped);
      return true;
    }
  }

  /**
   * Delivers everything buffered so far as one batch. A failed delivery keeps every entry, so the
   * next flush's batch just grows, unless the server rejected the batch (it would reject it again)
   * or this is the {@link DeliveryResult#MAX_FAILED_FLUSHES}th flush in a row to fail: then the
   * batch is dropped, which is also what makes room in a full buffer again.
   */
  public void flush() {
    List<Map<String, Object>> snapshot;
    synchronized (lock) {
      if (entries.isEmpty()) {
        return;
      }
      snapshot = new ArrayList<>(entries);
    }

    DeliveryResult result = deliver.apply(snapshot);

    synchronized (lock) {
      if (result == DeliveryResult.FAILED && ++failedFlushes < DeliveryResult.MAX_FAILED_FLUSHES) {
        return;
      }
      failedFlushes = 0;
      // Exactly the entries just delivered (or given up on): anything recorded while the request
      // was in flight sits after them and stays for the next flush.
      entries.subList(0, Math.min(snapshot.size(), entries.size())).clear();
    }
    if (result != DeliveryResult.DELIVERED) {
      configuration.log("dropped " + snapshot.size() + " metrics: " + result.dropReason());
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
      ScheduledExecutorService scheduled = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "forge-ops-tracker-metric-flusher");
        thread.setDaemon(true);
        return thread;
      });
      long intervalSeconds = Math.max(1, interval.get().toSeconds());
      scheduled.scheduleWithFixedDelay(this::flushSafely, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
      executor = scheduled;
      if (shutdownHookRegistered.compareAndSet(false, true)) {
        Runtime.getRuntime()
            .addShutdownHook(new Thread(this::flushSafely, "forge-ops-tracker-metric-flusher-shutdown"));
      }
    }
  }

  /**
   * Test-only, the same purpose as {@link PerformanceFlusher#shutdown}: stops the schedule and drops
   * what is buffered.
   */
  void shutdown() {
    ScheduledExecutorService current = executor;
    if (current != null) {
      current.shutdownNow();
    }
    synchronized (lock) {
      entries.clear();
    }
  }

  int size() {
    synchronized (lock) {
      return entries.size();
    }
  }

  private void flushSafely() {
    try {
      flush();
    } catch (RuntimeException e) {
      configuration.log("metric flush error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }
}
