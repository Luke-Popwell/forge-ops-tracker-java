package io.github.lukepopwell.tracker;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

/** Same shape as {@link DeliveryQueue}, delivering finished traces to {@code /spans}. */
public class SpanQueue {
  private final Configuration configuration;
  private final Client client;
  private final BlockingQueue<Map<String, Object>> queue;
  private final ReentrantLock startLock = new ReentrantLock();
  private volatile Thread worker;

  public SpanQueue(Configuration configuration) {
    this(configuration, new Client(configuration));
  }

  SpanQueue(Configuration configuration, Client client) {
    this.configuration = configuration;
    this.client = client;
    this.queue = new ArrayBlockingQueue<>(configuration.getQueueSize());
  }

  public boolean push(Map<String, Object> trace) {
    ensureWorkerStarted();
    boolean accepted = queue.offer(trace);
    if (!accepted) {
      configuration.log("span queue full, dropping trace");
    }
    return accepted;
  }

  void shutdown() {
    Thread current = worker;
    if (current != null) {
      current.interrupt();
    }
  }

  private void ensureWorkerStarted() {
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
      Thread thread = new Thread(this::run, "forge-ops-tracker-spans");
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
        client.deliverSpans(queue.take());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        configuration.log("span thread error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
      }
    }
  }
}
