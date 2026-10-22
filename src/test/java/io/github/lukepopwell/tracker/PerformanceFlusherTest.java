package io.github.lukepopwell.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PerformanceFlusherTest {
  private static Configuration newConfiguration() {
    Configuration config = new Configuration();
    config.setRelease("1.2.3");
    config.setEnvironment("production");
    return config;
  }

  @Test
  void strictPrivacyTakesTheIdsOutOfARawPathTransactionNameAndKeepsARoute() {
    List<List<Map<String, Object>>> delivered = new CopyOnWriteArrayList<>();
    Configuration config = newConfiguration();
    config.setStrictPrivacy(true);

    PerformanceFlusher flusher = new PerformanceFlusher(config, new RecordingClient(delivered));
    flusher.record("GET /patients/42", 100.0);
    flusher.record("GET /patients/43", 100.0);
    flusher.record("GET /posts/{id}", 100.0);
    flusher.flush();

    assertEquals(List.of("GET /patients/:path", "GET /posts/{id}"),
        delivered.get(0).stream().map(s -> s.get("transaction_name")).sorted().toList());
  }

  @Test
  void bucketsByTransactionNameAndDeliversOneAggregateEntryPerTransaction() {
    List<List<Map<String, Object>>> delivered = new CopyOnWriteArrayList<>();
    RecordingClient client = new RecordingClient(delivered);

    PerformanceFlusher flusher = new PerformanceFlusher(newConfiguration(), client);
    flusher.record("GET /posts/{id}", 100.0);
    flusher.record("GET /posts/{id}", 200.0);
    flusher.record("GET /posts", 50.0);
    flusher.flush();

    assertEquals(1, delivered.size());
    List<Map<String, Object>> samples = delivered.get(0);
    assertEquals(2, samples.size());

    Map<String, Object> show = samples.stream()
        .filter(s -> "GET /posts/{id}".equals(s.get("transaction_name")))
        .findFirst().orElseThrow();
    assertEquals(2L, show.get("request_count"));
    assertEquals(300.0, show.get("duration_sum_ms"));
    assertEquals(200.0, show.get("max_duration_ms"));
    assertEquals("1.2.3", show.get("release"));
    assertEquals("production", show.get("environment"));

    Map<String, Object> index = samples.stream()
        .filter(s -> "GET /posts".equals(s.get("transaction_name")))
        .findFirst().orElseThrow();
    assertEquals(1L, index.get("request_count"));
    assertEquals(50.0, index.get("duration_sum_ms"));

    // Nothing new was recorded since the successful flush above, so this one sends nothing.
    flusher.flush();
    assertEquals(1, delivered.size());

    flusher.shutdown();
  }

  @Test
  void doesNothingOnAFlushWithNothingRecorded() {
    AtomicInteger callCount = new AtomicInteger();
    Client client = new Client(newConfiguration()) {
      @Override
      public boolean deliverPerformanceSamples(List<Map<String, Object>> samples) {
        callCount.incrementAndGet();
        return true;
      }
    };

    PerformanceFlusher flusher = new PerformanceFlusher(newConfiguration(), client);
    flusher.flush();

    assertEquals(0, callCount.get());
    flusher.shutdown();
  }

  @Test
  void keepsEveryBucketForTheNextFlushInsteadOfLosingThemWhenDeliveryFails() {
    List<List<Map<String, Object>>> delivered = new CopyOnWriteArrayList<>();
    AtomicBoolean shouldSucceed = new AtomicBoolean(false);
    Client client = new Client(newConfiguration()) {
      @Override
      public boolean deliverPerformanceSamples(List<Map<String, Object>> samples) {
        if (!shouldSucceed.get()) {
          return false;
        }
        delivered.add(samples);
        return true;
      }
    };

    PerformanceFlusher flusher = new PerformanceFlusher(newConfiguration(), client);
    flusher.record("GET /posts/{id}", 100.0);
    flusher.flush(); // fails; the bucket must not be reset

    shouldSucceed.set(true);
    flusher.record("GET /posts/{id}", 100.0);
    flusher.flush();

    assertEquals(2L, delivered.get(0).get(0).get("request_count"));
    assertEquals(200.0, delivered.get(0).get(0).get("duration_sum_ms"));
    flusher.shutdown();
  }

  @Test
  void dropsABatchTheServerRejectsAndNeverSendsItAgain() throws IOException {
    List<String> log = new CopyOnWriteArrayList<>();
    try (StatusServer server = new StatusServer("/api/v1/performance_samples", 403)) {
      Configuration config = server.configuration(log);
      PerformanceFlusher flusher = new PerformanceFlusher(config, new Client(config));
      flusher.record("GET /posts/{id}", 100.0);
      flusher.flush(); // rejected: the bucket is let go
      flusher.flush(); // nothing left to send

      assertEquals(1, server.bodies.size());
      assertEquals(1, log.stream().filter(line -> line.contains("dropped performance samples")).count());

      server.respondWith(202);
      flusher.record("GET /posts", 50.0);
      flusher.flush();

      assertEquals(2, server.bodies.size());
      assertFalse(server.bodies.get(1).contains("GET /posts/{id}"));
      assertTrue(server.bodies.get(1).contains("\"request_count\":1"));
      flusher.shutdown();
    }
  }

  @Test
  void keepsABatchTheServerAnswersWithA5xx() throws IOException {
    List<String> log = new CopyOnWriteArrayList<>();
    try (StatusServer server = new StatusServer("/api/v1/performance_samples", 503)) {
      Configuration config = server.configuration(log);
      PerformanceFlusher flusher = new PerformanceFlusher(config, new Client(config));
      flusher.record("GET /posts/{id}", 100.0);
      flusher.flush(); // fails for now; the bucket stays

      server.respondWith(202);
      flusher.record("GET /posts/{id}", 100.0);
      flusher.flush();

      assertEquals(2, server.bodies.size());
      assertTrue(server.bodies.get(1).contains("\"request_count\":2"));
      flusher.shutdown();
    }
  }

  @Test
  void dropsABatchOnTheFifthFailedFlushInARow() {
    AtomicInteger attempts = new AtomicInteger();
    List<String> log = new CopyOnWriteArrayList<>();
    Configuration config = newConfiguration();
    config.setLogger(log::add);
    Client client = new Client(config) {
      @Override
      public boolean deliverPerformanceSamples(List<Map<String, Object>> samples) {
        attempts.incrementAndGet();
        return false;
      }
    };

    PerformanceFlusher flusher = new PerformanceFlusher(config, client);
    flusher.record("GET /posts/{id}", 100.0);
    for (int i = 0; i < 4; i++) {
      flusher.flush();
    }
    assertTrue(log.isEmpty()); // four failures in: still kept

    flusher.flush(); // the fifth: dropped
    flusher.flush(); // nothing left to send

    assertEquals(5, attempts.get());
    assertEquals(1, log.stream().filter(line -> line.contains("delivery failed 5 times in a row")).count());
    flusher.shutdown();
  }

  @Test
  void aDeliveredFlushStartsTheCountOfFailedFlushesOver() {
    AtomicInteger attempts = new AtomicInteger();
    Client client = new Client(newConfiguration()) {
      @Override
      public boolean deliverPerformanceSamples(List<Map<String, Object>> samples) {
        return attempts.incrementAndGet() == 5; // four failures, one success, then failures again
      }
    };

    PerformanceFlusher flusher = new PerformanceFlusher(newConfiguration(), client);
    flusher.record("GET /posts/{id}", 100.0);
    for (int i = 0; i < 5; i++) {
      flusher.flush();
    }
    flusher.record("GET /posts/{id}", 100.0);
    for (int i = 0; i < 4; i++) {
      flusher.flush();
    }
    flusher.flush(); // only the fifth failure since the success drops it
    flusher.flush();

    assertEquals(10, attempts.get());
    flusher.shutdown();
  }

  @Test
  void theScheduledExecutorFlushesOnItsOwnAfterTheConfiguredInterval() throws InterruptedException {
    CountDownLatch delivered = new CountDownLatch(1);
    List<List<Map<String, Object>>> received = new CopyOnWriteArrayList<>();
    RecordingClient client = new RecordingClient(received, delivered);

    Configuration config = newConfiguration();
    config.setPerformanceFlushInterval(Duration.ofMillis(1)); // rounded up to 1s by getPerformanceFlushInterval's own floor

    PerformanceFlusher flusher = new PerformanceFlusher(config, client);
    flusher.record("GET /posts/{id}", 100.0);

    assertTrue(delivered.await(3, TimeUnit.SECONDS), "performance sample was not delivered in time");
    assertEquals(1L, received.get(0).get(0).get("request_count"));

    flusher.shutdown();
  }

  @Test
  void aRecordThatLandsWhileDeliveryIsInFlightIsNeverLost() {
    List<List<Map<String, Object>>> received = new CopyOnWriteArrayList<>();
    PerformanceFlusher[] holder = new PerformanceFlusher[1];
    // The exact window between the snapshot and delivery succeeding.
    Client client = new Client(new Configuration()) {
      @Override
      public boolean deliverPerformanceSamples(List<Map<String, Object>> samples) {
        received.add(samples);
        if (received.size() == 1) {
          holder[0].record("GET /posts/{id}", 25.0);
          holder[0].record("GET /new", 7.0);
        }
        return true;
      }
    };
    PerformanceFlusher flusher = new PerformanceFlusher(newConfiguration(), client);
    holder[0] = flusher;

    flusher.record("GET /posts/{id}", 10.0);
    flusher.flush();
    flusher.flush();

    assertEquals(2, received.size());
    assertEquals(1, received.get(0).size());
    Map<String, Map<String, Object>> second = new java.util.HashMap<>();
    received.get(1).forEach(sample -> second.put((String) sample.get("transaction_name"), sample));
    assertEquals(2, second.size());
    assertEquals(1L, second.get("GET /posts/{id}").get("request_count"));
    assertEquals(25.0, second.get("GET /posts/{id}").get("duration_sum_ms"));
    assertEquals(7.0, second.get("GET /new").get("duration_sum_ms"));

    flusher.shutdown();
  }

  @Test
  @SuppressWarnings("unchecked")
  void deliversALatencyHistogramPerBucketAlongsideCountSumAndMax() {
    List<List<Map<String, Object>>> delivered = new CopyOnWriteArrayList<>();
    PerformanceFlusher flusher = new PerformanceFlusher(newConfiguration(), new RecordingClient(delivered));

    for (double duration : new double[] {10.0, 40.0, 120.0, 700.0, 12_000.0}) {
      flusher.record("GET /posts", duration);
    }
    flusher.flush();

    Map<String, Long> histogram = (Map<String, Long>) delivered.get(0).get(0).get("histogram");
    assertEquals(Map.of("50", 2L, "250", 1L, "1000", 1L, "inf", 1L), histogram);
    assertEquals(5L, histogram.values().stream().mapToLong(Long::longValue).sum());
    flusher.shutdown();
  }

  @Test
  @SuppressWarnings("unchecked")
  void keepsHistogramCountsForTheNextFlushWhenDeliveryFails() {
    List<List<Map<String, Object>>> delivered = new CopyOnWriteArrayList<>();
    AtomicBoolean shouldSucceed = new AtomicBoolean(false);
    Client client = new Client(newConfiguration()) {
      @Override
      public boolean deliverPerformanceSamples(List<Map<String, Object>> samples) {
        if (!shouldSucceed.get()) {
          return false;
        }
        delivered.add(samples);
        return true;
      }
    };

    PerformanceFlusher flusher = new PerformanceFlusher(newConfiguration(), client);
    flusher.record("GET /posts", 10.0);
    flusher.flush();

    shouldSucceed.set(true);
    flusher.record("GET /posts", 300.0);
    flusher.flush();

    assertEquals(Map.of("50", 1L, "500", 1L), delivered.get(0).get(0).get("histogram"));
    flusher.shutdown();
  }

  @Test
  @SuppressWarnings("unchecked")
  void aHistogramCountRecordedDuringDeliveryIsSentOnTheNextFlush() {
    List<List<Map<String, Object>>> received = new CopyOnWriteArrayList<>();
    PerformanceFlusher[] holder = new PerformanceFlusher[1];
    Client client = new Client(new Configuration()) {
      @Override
      public boolean deliverPerformanceSamples(List<Map<String, Object>> samples) {
        received.add(samples);
        if (received.size() == 1) {
          holder[0].record("GET /posts", 300.0);
          holder[0].record("GET /new", 5.0);
        }
        return true;
      }
    };
    PerformanceFlusher flusher = new PerformanceFlusher(newConfiguration(), client);
    holder[0] = flusher;

    flusher.record("GET /posts", 10.0);
    flusher.flush();
    flusher.flush();

    assertEquals(Map.of("50", 1L), received.get(0).get(0).get("histogram"));
    Map<String, Map<String, Object>> second = new java.util.HashMap<>();
    received.get(1).forEach(sample -> second.put((String) sample.get("transaction_name"), sample));
    assertEquals(Map.of("500", 1L), second.get("GET /posts").get("histogram"));
    assertEquals(Map.of("50", 1L), second.get("GET /new").get("histogram"));
    flusher.shutdown();
  }

  private static class RecordingClient extends Client {
    private final List<List<Map<String, Object>>> received;
    private final CountDownLatch delivered;

    RecordingClient(List<List<Map<String, Object>>> received) {
      this(received, null);
    }

    RecordingClient(List<List<Map<String, Object>>> received, CountDownLatch delivered) {
      super(new Configuration());
      this.received = received;
      this.delivered = delivered;
    }

    @Override
    public boolean deliverPerformanceSamples(List<Map<String, Object>> samples) {
      received.add(samples);
      if (delivered != null) {
        delivered.countDown();
      }
      return true;
    }
  }
}
