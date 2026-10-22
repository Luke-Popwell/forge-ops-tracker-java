package io.github.lukepopwell.tracker;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A small background thread + bounded queue, so delivery never blocks the request/call site that
 * caught the error. Unlike the PHP and Node clients (no real background thread available there:
 * PHP-FPM is shared-nothing per request, Node is single-threaded), the JVM has real native
 * threads, so this is architecturally closer to the Ruby gem's own {@code Thread.new}-backed
 * queue: one daemon worker thread, started lazily on first {@link #push}.
 *
 * <p>The worker thread is marked a daemon specifically so a host app can still shut its JVM down
 * cleanly without this client's thread keeping it alive: it never needs an explicit stop signal
 * to avoid hanging a shutdown. A daemon thread also dies with the JVM, though, so the first push
 * registers a shutdown hook that sends whatever is still queued before the JVM exits (see {@link
 * #drain}).
 */
public class DeliveryQueue {
  /**
   * The most a normal JVM exit waits for queued events to go out (see {@link #drain}): long enough
   * for one delivery at the default connect + read timeouts, short enough that an unreachable
   * ForgeOps never holds up a deploy or a cron run for long.
   */
  public static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(5);

  private final Configuration configuration;
  private final Client client;
  private final BlockingQueue<Job> queue;
  private final ReentrantLock startLock = new ReentrantLock();
  private final AtomicBoolean shutdownHookRegistered = new AtomicBoolean();
  /** Jobs accepted but not yet delivered: still queued, or being delivered right now. */
  private final AtomicInteger unfinished = new AtomicInteger();
  private volatile Thread worker;

  public DeliveryQueue(Configuration configuration) {
    this(configuration, new Client(configuration));
  }

  DeliveryQueue(Configuration configuration, Client client) {
    this.configuration = configuration;
    this.client = client;
    this.queue = new ArrayBlockingQueue<>(configuration.getQueueSize());
  }

  /** Which endpoint a queued payload goes to: errors, recorded changes and change snapshots share one worker. */
  private enum Target { EVENT, CHANGE, CHANGE_SNAPSHOT }

  private record Job(Target target, Map<String, Object> payload) {}

  /**
   * Enqueues a payload, dropping it silently (never blocking the caller) if the queue is already
   * full: a burst of exceptions must never apply backpressure to the host app.
   */
  public boolean push(Map<String, Object> payload) {
    return offer(new Job(Target.EVENT, payload), "event");
  }

  /** Same as {@link #push}, delivering to {@code /changes}. */
  public boolean pushChange(Map<String, Object> change) {
    return offer(new Job(Target.CHANGE, change), "change");
  }

  /** Same as {@link #push}, delivering to {@code /change_snapshots}. */
  public boolean pushChangeSnapshot(Map<String, Object> snapshot) {
    return offer(new Job(Target.CHANGE_SNAPSHOT, snapshot), "change snapshot");
  }

  /**
   * Sends whatever is still queued, and lets a delivery already under way finish, within {@code
   * timeout}; returns whether everything accepted so far has gone out. Delivers on the calling
   * thread rather than waiting on the worker. The shutdown hook calls this with {@link
   * #DRAIN_TIMEOUT}; without it a short-lived program (a CLI {@code main}, a cron job, a crash
   * reported by the uncaught-exception handler) lost every error it reported in its last moments,
   * since the daemon worker dies with the JVM before it gets to them.
   */
  public boolean drain(Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    try {
      while (System.nanoTime() - deadline < 0) {
        Job job = queue.poll();
        if (job == null) {
          break;
        }
        deliverAndFinish(job);
      }
      while (unfinished.get() > 0 && System.nanoTime() - deadline < 0) {
        Thread.sleep(10);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException e) {
      configuration.log("drain failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
    return unfinished.get() == 0;
  }

  private boolean offer(Job job, String what) {
    ensureWorkerStarted();
    unfinished.incrementAndGet();
    boolean accepted = queue.offer(job);
    if (!accepted) {
      unfinished.decrementAndGet();
      configuration.log("delivery queue full, dropping " + what);
    }
    return accepted;
  }

  private void ensureWorkerStarted() {
    registerShutdownHook();
    Thread current = worker;
    if (current != null && current.isAlive()) {
      return;
    }
    startLock.lock();
    try {
      current = worker;
      if (current != null && current.isAlive()) {
        return;
      }
      Thread thread = new Thread(this::run, "forge-ops-tracker-delivery");
      thread.setDaemon(true);
      thread.start();
      worker = thread;
    } finally {
      startLock.unlock();
    }
  }

  private void run() {
    while (true) {
      try {
        deliverAndFinish(queue.take());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        configuration.log("delivery thread error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
      }
    }
  }

  private void deliverAndFinish(Job job) {
    try {
      switch (job.target()) {
        case CHANGE -> client.deliverChange(job.payload());
        case CHANGE_SNAPSHOT -> client.deliverChangeSnapshot(job.payload());
        default -> client.deliver(job.payload());
      }
    } finally {
      unfinished.decrementAndGet();
    }
  }

  /**
   * Registered on the first push, not at construction, so a program that never reports anything
   * never waits on exit at all.
   */
  private void registerShutdownHook() {
    if (!shutdownHookRegistered.compareAndSet(false, true)) {
      return;
    }
    try {
      Runtime.getRuntime()
          .addShutdownHook(new Thread(() -> drain(DRAIN_TIMEOUT), "forge-ops-tracker-delivery-shutdown"));
    } catch (IllegalStateException e) {
      // Pushed from inside another shutdown hook: too late to add one, but the worker keeps
      // delivering while the other hooks run.
      configuration.log("delivery shutdown hook not registered: JVM is already shutting down");
    }
  }
}
