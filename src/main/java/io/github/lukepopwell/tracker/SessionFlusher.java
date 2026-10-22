package io.github.lukepopwell.tracker;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Counts requests and crashes in-process (see the servlet session-tracking filter) and
 * periodically flushes the totals as one small aggregate report, rather than one network call
 * per request. Ported from
 * {@code gems/forge_ops_tracker/lib/forge_ops_tracker/session_flusher.rb}.
 *
 * <p>Deliberately does not copy {@link DeliveryQueue}'s own hand-rolled daemon
 * {@code Thread}/{@code BlockingQueue} shape: that pattern exists specifically for a
 * push-triggered consumer ({@code queue.take()} blocks until work arrives). A flusher's trigger
 * is a clock, not a per-event signal, so a {@link ScheduledExecutorService} (a real primitive
 * for exactly this, unlike Ruby, which has none worth reaching for over a plain thread + sleep)
 * is the more idiomatic and more robust choice here. A shutdown hook covers the JVM-exit case
 * the way Ruby's {@code at_exit} does; unlike Node's SIGTERM/SIGINT (which override the
 * process's own default disposition and can race a host app's own handling), a JVM shutdown
 * hook is the standard, safe mechanism multiple independent libraries are meant to register
 * concurrently, so no equivalent risk applies here.
 */
public class SessionFlusher {
  private final Configuration configuration;
  private final Client client;
  private final Object lock = new Object();
  private long sessionsCount;
  private long crashedSessionsCount;
  private Instant periodStartedAt = Instant.now();
  private int failedFlushes;
  private volatile ScheduledExecutorService executor;
  private final AtomicBoolean shutdownHookRegistered = new AtomicBoolean();

  public SessionFlusher(Configuration configuration, Client client) {
    this.configuration = configuration;
    this.client = client;
  }

  public void recordSession(boolean crashed) {
    ensureScheduled();
    synchronized (lock) {
      sessionsCount++;
      if (crashed) {
        crashedSessionsCount++;
      }
    }
  }

  /**
   * Snapshots and resets the in-process counters, then delivers them. A failed delivery keeps
   * the counts where they are rather than resetting, so the next flush's window just grows
   * instead of losing what was already tallied; there's no other copy of this data anywhere.
   * Two limits on that: a checkin the server rejected would be rejected again however often it is
   * re-sent, so its counts are dropped at once, and after
   * {@link DeliveryResult#MAX_FAILED_FLUSHES} failed flushes in a row the counts are dropped too
   * and the window starts clean.
   */
  public void flush() {
    Map<String, Object> snapshot;
    synchronized (lock) {
      if (sessionsCount == 0) {
        return;
      }
      snapshot = new LinkedHashMap<>();
      snapshot.put("release", configuration.getRelease());
      snapshot.put("environment", configuration.getEnvironment());
      snapshot.put("period_started_at", periodStartedAt.toString());
      snapshot.put("period_ended_at", Instant.now().toString());
      snapshot.put("sessions_count", sessionsCount);
      snapshot.put("crashed_sessions_count", crashedSessionsCount);
    }

    DeliveryResult result = client.outcome(() -> client.deliverSessionCheckin(snapshot));

    synchronized (lock) {
      if (result == DeliveryResult.FAILED && ++failedFlushes < DeliveryResult.MAX_FAILED_FLUSHES) {
        return;
      }
      failedFlushes = 0;
      sessionsCount = 0;
      crashedSessionsCount = 0;
      periodStartedAt = Instant.now();
    }
    if (result != DeliveryResult.DELIVERED) {
      configuration.log("dropped a session checkin of " + snapshot.get("sessions_count") + " sessions: "
          + result.dropReason());
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
        Thread thread = new Thread(runnable, "forge-ops-tracker-session-flusher");
        thread.setDaemon(true);
        return thread;
      };
      ScheduledExecutorService scheduled = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory);
      long intervalSeconds = Math.max(1, configuration.getSessionFlushInterval().toSeconds());
      scheduled.scheduleWithFixedDelay(this::flushSafely, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
      executor = scheduled;
      registerShutdownHook();
    }
  }

  /**
   * Stops the scheduled flush entirely; the underlying executor's own scheduled task would
   * otherwise keep firing indefinitely against this instance's own (possibly by-then-stale)
   * configuration. Test-only in practice today (see {@link ForgeOpsTracker#resetForTesting});
   * a real host app's own JVM shutdown hook is what stops delivery in production, not this.
   */
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
      configuration.log("session flush error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private void registerShutdownHook() {
    if (!shutdownHookRegistered.compareAndSet(false, true)) {
      return;
    }
    Runtime.getRuntime().addShutdownHook(new Thread(this::flushSafely, "forge-ops-tracker-session-flusher-shutdown"));
  }
}
