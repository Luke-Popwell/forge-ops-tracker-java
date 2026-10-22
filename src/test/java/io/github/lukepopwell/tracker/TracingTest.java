package io.github.lukepopwell.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TracingTest {
  private HttpServer server;
  private final CountDownLatch received = new CountDownLatch(1);
  private final AtomicReference<String> body = new AtomicReference<>();

  @BeforeEach
  void start() throws Exception {
    ForgeOpsTracker.resetForTesting();
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/api/v1/spans", exchange -> {
      body.set(new String(exchange.getRequestBody().readAllBytes()));
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      received.countDown();
    });
    server.start();
  }

  @AfterEach
  void stop() {
    ForgeOpsTracker.resetForTesting();
    server.stop(0);
  }

  private void init(boolean tracing) {
    ForgeOpsTracker.init(config -> {
      config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
      config.setEnvironment("production");
      config.setTrackTracing(tracing);
      config.setTraceCaptureThreshold(Duration.ofMillis(10));
    }, false);
  }

  @Test
  void deliversASlowTraceWithNestedSpansToTheSpansEndpoint() throws Exception {
    init(true);
    ForgeOpsTracker.beginTrace();
    String result = ForgeOpsTracker.span("charge", "service", Map.of("order", 1), () -> {
      ForgeOpsTracker.recordSpan("SELECT", "database", Instant.now(), 3, null);
      return "ok";
    });
    assertEquals("ok", result);
    ForgeOpsTracker.finishTrace("GET /checkout", Instant.now(), 250);

    assertTrue(received.await(3, TimeUnit.SECONDS), "trace was never delivered");
    String json = body.get();
    assertTrue(json.contains("\"trace_id\""));
    assertTrue(json.contains("\"name\":\"GET /checkout\""));
    assertTrue(json.contains("\"name\":\"charge\""));
    assertTrue(json.contains("\"name\":\"SELECT\""));
    assertTrue(json.contains("\"parent_span_id\":null"));
  }

  @Test
  void spanRecordsAndRethrowsWhenTheWorkThrows() throws Exception {
    init(true);
    ForgeOpsTracker.beginTrace();
    RuntimeException failure = new RuntimeException("boom");
    RuntimeException thrown = assertThrows(RuntimeException.class, () -> ForgeOpsTracker.span("bad", () -> {
      throw failure;
    }));
    assertEquals(failure, thrown);
    ForgeOpsTracker.finishTrace("GET /x", Instant.now(), 250);
    assertTrue(received.await(3, TimeUnit.SECONDS));
    assertTrue(body.get().contains("\"name\":\"bad\""));
  }

  @Test
  void spanJustRunsTheWorkOutsideATrace() throws Exception {
    init(true);
    assertEquals(7, ForgeOpsTracker.span("free", () -> 7));
  }

  @Test
  void sendsNothingWhenTheRootIsUnderTheThreshold() throws Exception {
    init(true);
    ForgeOpsTracker.beginTrace();
    ForgeOpsTracker.finishTrace("GET /fast", Instant.now(), 1);
    assertTrue(!received.await(300, TimeUnit.MILLISECONDS));
  }

  @Test
  void trackTracingOffDisablesTheWholeFeature() throws Exception {
    init(false);
    ForgeOpsTracker.beginTrace();
    ForgeOpsTracker.span("x", () -> null);
    ForgeOpsTracker.finishTrace("GET /x", Instant.now(), 500);
    assertTrue(!received.await(300, TimeUnit.MILLISECONDS));
  }
}
