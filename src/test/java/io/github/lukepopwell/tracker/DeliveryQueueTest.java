package io.github.lukepopwell.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DeliveryQueueTest {
  @Test
  void deliversPushedPayloadsOnItsBackgroundThread() throws InterruptedException {
    CountDownLatch delivered = new CountDownLatch(1);
    List<Map<String, Object>> received = new CopyOnWriteArrayList<>();
    RecordingClient client = new RecordingClient(received, delivered);
    DeliveryQueue queue = new DeliveryQueue(new Configuration(), client);

    Thread callingThread = Thread.currentThread();
    assertTrue(queue.push(Map.of("message", "boom")));

    assertTrue(delivered.await(2, TimeUnit.SECONDS), "payload was not delivered in time");
    assertEquals(1, received.size());
    assertEquals("boom", received.get(0).get("message"));
    // The delivery itself must happen off the calling thread: that's the entire point of a
    // background queue.
    assertTrue(client.deliveredOnThread != callingThread);
  }

  @Test
  void dropsPayloadsSilentlyOnceTheQueueIsFull() throws InterruptedException {
    CountDownLatch releaseWorker = new CountDownLatch(1);
    BlockingClient client = new BlockingClient(releaseWorker);
    Configuration config = new Configuration();
    config.setQueueSize(1);
    DeliveryQueue queue = new DeliveryQueue(config, client);

    // First push is picked up immediately by the worker and blocks there, so the queue itself
    // stays empty for the second push to fill.
    assertTrue(queue.push(Map.of("n", 1)));
    Thread.sleep(100);
    assertTrue(queue.push(Map.of("n", 2)));
    // Third push must be dropped, not block the caller, since the queue (size 1) is now full and
    // the worker is still stuck delivering the first payload.
    assertEquals(false, queue.push(Map.of("n", 3)));

    releaseWorker.countDown();
  }

  @Test
  void drainDeliversWhatIsQueuedOnTheCallingThreadAndWaitsForTheWorker() throws InterruptedException {
    Map<Object, Thread> deliveredOn = new ConcurrentHashMap<>();
    // n=1 keeps the worker busy for the whole drain, so the drain itself has to deliver the rest.
    SlowClient client = new SlowClient(500, 0, deliveredOn);
    Configuration config = new Configuration();
    config.setQueueSize(10);
    DeliveryQueue queue = new DeliveryQueue(config, client);

    queue.push(Map.of("n", 1));
    Thread.sleep(100); // the worker is now busy delivering n=1
    queue.push(Map.of("n", 2));
    queue.push(Map.of("n", 3));

    assertTrue(queue.drain(Duration.ofSeconds(5)));
    assertEquals(3, deliveredOn.size());
    assertTrue(deliveredOn.get(1) != Thread.currentThread());
    assertEquals(Thread.currentThread(), deliveredOn.get(2));
    assertEquals(Thread.currentThread(), deliveredOn.get(3));
  }

  @Test
  void drainGivesUpAtTheTimeoutRatherThanHoldingTheExitOpen() {
    Configuration config = new Configuration();
    config.setQueueSize(10);
    DeliveryQueue queue = new DeliveryQueue(config, new SlowClient(500, 500, new ConcurrentHashMap<>()));
    for (int n = 0; n < 3; n++) {
      queue.push(Map.of("n", n));
    }

    long started = System.nanoTime();
    assertFalse(queue.drain(Duration.ofMillis(200)));
    assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1));
  }

  @Test
  void drainWaitsForNothingWhenNothingWasReported() {
    DeliveryQueue queue = new DeliveryQueue(new Configuration(), new SlowClient(500, 500, Map.of()));

    long started = System.nanoTime();
    assertTrue(queue.drain(DeliveryQueue.DRAIN_TIMEOUT));
    assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(100));
  }

  @Test
  void aDroppedPayloadIsNotWaitedFor() throws InterruptedException {
    CountDownLatch releaseWorker = new CountDownLatch(1);
    Configuration config = new Configuration();
    config.setQueueSize(1);
    DeliveryQueue queue = new DeliveryQueue(config, new BlockingClient(releaseWorker));
    queue.push(Map.of("n", 1));
    Thread.sleep(100);
    queue.push(Map.of("n", 2));
    assertFalse(queue.push(Map.of("n", 3)));

    releaseWorker.countDown();
    assertTrue(queue.drain(Duration.ofSeconds(2)));
  }

  private static class SlowClient extends Client {
    private final long firstMillis;
    private final long restMillis;
    private final Map<Object, Thread> deliveredOn;

    SlowClient(long firstMillis, long restMillis, Map<Object, Thread> deliveredOn) {
      super(new Configuration());
      this.firstMillis = firstMillis;
      this.restMillis = restMillis;
      this.deliveredOn = deliveredOn;
    }

    @Override
    public boolean deliver(Map<String, Object> payload) {
      try {
        Thread.sleep(Integer.valueOf(1).equals(payload.get("n")) ? firstMillis : restMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      deliveredOn.put(payload.get("n"), Thread.currentThread());
      return true;
    }
  }

  private static class RecordingClient extends Client {
    private final List<Map<String, Object>> received;
    private final CountDownLatch delivered;
    private volatile Thread deliveredOnThread;

    RecordingClient(List<Map<String, Object>> received, CountDownLatch delivered) {
      super(new Configuration());
      this.received = received;
      this.delivered = delivered;
    }

    @Override
    public boolean deliver(Map<String, Object> payload) {
      deliveredOnThread = Thread.currentThread();
      received.add(payload);
      delivered.countDown();
      return true;
    }
  }

  private static class BlockingClient extends Client {
    private final CountDownLatch releaseWorker;

    BlockingClient(CountDownLatch releaseWorker) {
      super(new Configuration());
      this.releaseWorker = releaseWorker;
    }

    @Override
    public boolean deliver(Map<String, Object> payload) {
      try {
        releaseWorker.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return true;
    }
  }
}
