package io.github.lukepopwell.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MetricBufferTest {
  private final List<MetricBuffer> buffers = new ArrayList<>();
  private HttpServer server;

  @AfterEach
  void cleanup() {
    buffers.forEach(MetricBuffer::shutdown);
    ForgeOpsTracker.resetForTesting();
    if (server != null) {
      server.stop(0);
    }
  }

  private static Configuration newConfiguration() {
    Configuration config = new Configuration();
    config.setDsn("https://key@tracker.example.com/api/v1/events");
    config.setEnvironment("production");
    config.setRelease("1.2.3");
    return config;
  }

  private MetricBuffer buffer(java.util.function.Function<List<Map<String, Object>>, Boolean> deliver) {
    MetricBuffer buffer = new MetricBuffer(newConfiguration(), deliver, () -> Duration.ofHours(1));
    buffers.add(buffer);
    return buffer;
  }

  private static Map<String, Object> entry(String name, Object value) {
    return new java.util.LinkedHashMap<>(Map.of("metric_name", name, "value", value));
  }

  @Test
  void deliversEveryEntryAsOneBatchStampedWithRecordedAt() {
    List<List<Map<String, Object>>> delivered = new CopyOnWriteArrayList<>();
    MetricBuffer buffer = buffer(entries -> delivered.add(new ArrayList<>(entries)));

    buffer.record(entry("signup", 1.0));
    buffer.record(entry("payment", 49.5));
    buffer.flush();

    assertEquals(1, delivered.size());
    assertEquals(2, delivered.get(0).size());
    assertTrue(((String) delivered.get(0).get(0).get("recorded_at")).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ"));
  }

  @Test
  void keepsANegativeValueSinceARefundIsARealMetric() {
    List<Map<String, Object>> delivered = new CopyOnWriteArrayList<>();
    MetricBuffer buffer = buffer(entries -> delivered.addAll(entries));
    buffer.record(entry("refund", -12.0));
    buffer.flush();
    assertEquals(-12.0, delivered.get(0).get("value"));
  }

  @Test
  void dropsNaNInfiniteAndNonNumericValuesSoOneBadEntryCannotPoisonABatch() {
    MetricBuffer buffer = buffer(entries -> true);
    assertFalse(buffer.record(entry("a", Double.NaN)));
    assertFalse(buffer.record(entry("b", Double.POSITIVE_INFINITY)));
    assertFalse(buffer.record(entry("c", "12")));
    assertTrue(buffer.record(entry("d", 3)));
  }

  @Test
  void aFailedDeliveryKeepsEveryEntryForTheNextFlush() {
    AtomicInteger calls = new AtomicInteger();
    List<List<String>> delivered = new CopyOnWriteArrayList<>();
    MetricBuffer buffer = buffer(entries -> {
      delivered.add(entries.stream().map(e -> (String) e.get("metric_name")).toList());
      return calls.incrementAndGet() > 1;
    });

    buffer.record(entry("a", 1.0));
    buffer.flush();
    buffer.record(entry("b", 2.0));
    buffer.flush();
    buffer.flush(); // nothing left: no third delivery

    assertEquals(List.of(List.of("a"), List.of("a", "b")), delivered);
  }

  @Test
  void aRejectedBatchIsDroppedAndNeverSentAgain() {
    List<String> log = new CopyOnWriteArrayList<>();
    List<List<String>> sent = new CopyOnWriteArrayList<>();
    Configuration config = newConfiguration();
    config.setLogger(log::add);
    MetricBuffer buffer = new MetricBuffer(config, () -> Duration.ofHours(1), entries -> {
      sent.add(entries.stream().map(e -> (String) e.get("metric_name")).toList());
      return sent.size() == 1 ? DeliveryResult.REJECTED : DeliveryResult.DELIVERED;
    });
    buffers.add(buffer);

    buffer.record(entry("a", 1.0));
    buffer.flush(); // rejected: "a" is let go
    buffer.flush(); // nothing left to send
    assertEquals(0, buffer.size());

    buffer.record(entry("b", 2.0));
    buffer.flush();

    assertEquals(List.of(List.of("a"), List.of("b")), sent);
    assertEquals(List.of("[ForgeOpsTracker] dropped 1 metrics: the server rejected it"), log);
  }

  @Test
  void aBatchTheServerAnswersWith403IsNotSentAgainAndLeavesRoomForNewMetrics() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/api/v1/custom_metrics", exchange -> {
      requests.incrementAndGet();
      exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(403, -1);
      exchange.close();
    });
    server.start();

    ForgeOpsTracker.init(config -> {
      config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
      config.setEnvironment("production");
    }, false);
    for (int i = 0; i < MetricBuffer.MAX_ENTRIES + 10; i++) {
      ForgeOpsTracker.captureMetric("signup"); // fills the buffer; the last ten are refused
    }
    ForgeOpsTracker.flushMetrics();
    ForgeOpsTracker.flushMetrics();
    assertEquals(1, requests.get());

    ForgeOpsTracker.captureMetric("signup"); // kept again: the rejected batch no longer fills the buffer
    ForgeOpsTracker.flushMetrics();
    assertEquals(2, requests.get());
  }

  @Test
  void theFifthFailedFlushInARowDropsTheBatch() {
    List<String> log = new CopyOnWriteArrayList<>();
    AtomicInteger attempts = new AtomicInteger();
    Configuration config = newConfiguration();
    config.setLogger(log::add);
    MetricBuffer buffer = new MetricBuffer(config, entries -> {
      attempts.incrementAndGet();
      return false;
    }, () -> Duration.ofHours(1));
    buffers.add(buffer);

    buffer.record(entry("a", 1.0));
    for (int i = 0; i < 4; i++) {
      buffer.flush();
    }
    assertEquals(1, buffer.size()); // four failures in: still kept

    buffer.flush(); // the fifth: dropped
    buffer.flush(); // nothing left to send

    assertEquals(5, attempts.get());
    assertEquals(0, buffer.size());
    assertEquals(List.of("[ForgeOpsTracker] dropped 1 metrics: delivery failed 5 times in a row"), log);
  }

  @Test
  void anEntryRecordedWhileDeliveryIsInFlightIsNeverLost() throws Exception {
    CountDownLatch inFlight = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    List<List<String>> delivered = new CopyOnWriteArrayList<>();
    MetricBuffer buffer = buffer(entries -> {
      delivered.add(entries.stream().map(e -> (String) e.get("metric_name")).toList());
      if (delivered.size() == 1) {
        inFlight.countDown();
        try {
          release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      return true;
    });

    buffer.record(entry("first", 1.0));
    Thread flushing = new Thread(buffer::flush);
    flushing.start();
    assertTrue(inFlight.await(5, TimeUnit.SECONDS));
    buffer.record(entry("during", 2.0));
    release.countDown();
    flushing.join(5000);
    buffer.flush();

    assertEquals(List.of(List.of("first"), List.of("during")), delivered);
  }

  @Test
  void isCappedAndDropsFurtherEntriesUntilAFlushSucceeds() {
    MetricBuffer buffer = buffer(entries -> false);
    int accepted = 0;
    for (int i = 0; i < MetricBuffer.MAX_ENTRIES + 50; i++) {
      if (buffer.record(entry("m", 1.0))) {
        accepted++;
      }
    }
    assertEquals(MetricBuffer.MAX_ENTRIES, accepted);
  }

  @Test
  void captureMetricAndCaptureInfrastructureMetricDeliverToTheirOwnEndpointsEndToEnd() throws Exception {
    Map<String, String> bodies = new ConcurrentHashMap<>();
    Map<String, String> auth = new ConcurrentHashMap<>();
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    for (String path : List.of("/api/v1/custom_metrics", "/api/v1/infrastructure_metrics")) {
      server.createContext(path, exchange -> {
        bodies.put(path, new String(exchange.getRequestBody().readAllBytes()));
        auth.put(path, exchange.getRequestHeaders().getFirst("Authorization"));
        exchange.sendResponseHeaders(202, -1);
        exchange.close();
      });
    }
    server.start();

    ForgeOpsTracker.init(config -> {
      config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
      config.setEnvironment("production");
      config.setRelease("a1b2c3d");
    }, false);
    ForgeOpsTracker.captureMetric("signup");
    ForgeOpsTracker.captureMetric("payment", 49.0);
    ForgeOpsTracker.captureInfrastructureMetric("cpu", 0.42, "db-1");
    ForgeOpsTracker.captureInfrastructureMetric("memory", 0.7);
    ForgeOpsTracker.flushMetrics();

    String custom = bodies.get("/api/v1/custom_metrics");
    assertNotNull(custom);
    assertEquals("Bearer key", auth.get("/api/v1/custom_metrics"));
    assertTrue(custom.contains("\"metric_name\":\"signup\""));
    assertTrue(custom.contains("\"value\":49.0"));
    assertTrue(custom.contains("\"environment\":\"production\""));
    assertTrue(custom.contains("\"release\":\"a1b2c3d\""));
    String infrastructure = bodies.get("/api/v1/infrastructure_metrics");
    assertTrue(infrastructure.contains("\"hostname\":\"db-1\""));
    assertTrue(infrastructure.contains("\"hostname\":\"" + new Configuration().getServerName() + "\""));
  }

  @Test
  void captureMetricIsANoOpWhenTheClientIsNotEnabled() {
    ForgeOpsTracker.init(config -> {
      config.setDsn("https://key@tracker.example.com/api/v1/events");
      config.setEnvironment("development");
    }, false);
    ForgeOpsTracker.captureMetric("signup");
    ForgeOpsTracker.captureInfrastructureMetric("cpu", 1.0);
    ForgeOpsTracker.flushMetrics(); // returns without a buffer ever having been created
  }

  @Test
  void theMetricUrisSwapTheTrailingEventsSegment() {
    Configuration config = newConfiguration();
    assertEquals("https://tracker.example.com/api/v1/custom_metrics", config.getCustomMetricsUri().toString());
    assertEquals("https://tracker.example.com/api/v1/infrastructure_metrics", config.getInfrastructureMetricsUri().toString());
  }
}
