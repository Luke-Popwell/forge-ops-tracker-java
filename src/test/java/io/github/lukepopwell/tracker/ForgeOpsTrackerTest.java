package io.github.lukepopwell.tracker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ForgeOpsTrackerTest {
  @BeforeEach
  void reset() {
    ForgeOpsTracker.resetForTesting();
  }

  @AfterEach
  void cleanup() {
    ForgeOpsTracker.resetForTesting();
    // Belt-and-suspenders: Thread.setDefaultUncaughtExceptionHandler is JVM-wide global state,
    // shared with every other test class in this same test run: make sure nothing installed
    // here ever leaks past this test class regardless of what resetForTesting restores it to.
    Thread.setDefaultUncaughtExceptionHandler(null);
  }

  @Test
  void configurationIsASingletonUntilReset() {
    Configuration first = ForgeOpsTracker.configuration();
    Configuration second = ForgeOpsTracker.configuration();

    assertSame(first, second);
  }

  @Test
  void initAppliesTheConfigurerToTheSharedConfiguration() {
    ForgeOpsTracker.init(config -> {
      config.setDsn("https://key@example.com/api/v1/events");
      config.setEnvironment("production");
      // Enabled against a host that isn't a local stub: keep the startup change snapshot from going out.
      config.setDetectChanges(false);
    }, false);

    assertTrue(ForgeOpsTracker.configuration().isEnabled());
  }

  @Test
  void captureExceptionActuallyDeliversToTheConfiguredEndpoint() throws IOException, InterruptedException {
    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    CountDownLatch received = new CountDownLatch(1);
    AtomicReference<String> body = new AtomicReference<>();
    server.createContext("/api/v1/events", exchange -> {
      body.set(new String(exchange.getRequestBody().readAllBytes()));
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      received.countDown();
    });
    server.start();
    try {
      ForgeOpsTracker.init(config -> {
        config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
        config.setEnvironment("production");
      }, false);

      ForgeOpsTracker.captureException(new RuntimeException("end to end boom"));

      assertTrue(received.await(2, TimeUnit.SECONDS), "event was never delivered");
      assertTrue(body.get().contains("end to end boom"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void setUserAttachesTheUserToALaterCaptureExceptionCall() throws IOException, InterruptedException {
    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    CountDownLatch received = new CountDownLatch(1);
    AtomicReference<String> body = new AtomicReference<>();
    server.createContext("/api/v1/events", exchange -> {
      body.set(new String(exchange.getRequestBody().readAllBytes()));
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      received.countDown();
    });
    server.start();
    try {
      ForgeOpsTracker.init(config -> {
        config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
        config.setEnvironment("production");
      }, false);

      ForgeOpsTracker.setUser(java.util.Map.of("id", 42, "email", "alice@example.com"));
      ForgeOpsTracker.captureException(new RuntimeException("boom"));

      assertTrue(received.await(2, TimeUnit.SECONDS), "event was never delivered");
      assertTrue(body.get().contains("alice@example.com"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void anExplicitUserArgumentOverridesWhateverSetUserLastSet() throws IOException, InterruptedException {
    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    CountDownLatch received = new CountDownLatch(1);
    AtomicReference<String> body = new AtomicReference<>();
    server.createContext("/api/v1/events", exchange -> {
      body.set(new String(exchange.getRequestBody().readAllBytes()));
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      received.countDown();
    });
    server.start();
    try {
      ForgeOpsTracker.init(config -> {
        config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
        config.setEnvironment("production");
      }, false);

      ForgeOpsTracker.setUser(java.util.Map.of("id", 42));
      ForgeOpsTracker.captureException(new RuntimeException("boom"), java.util.Map.of(), java.util.Map.of("id", 99));

      assertTrue(received.await(2, TimeUnit.SECONDS), "event was never delivered");
      assertTrue(body.get().contains("\"id\":99"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void resetForTestingClearsTheCurrentUser() throws IOException, InterruptedException {
    ForgeOpsTracker.setUser(java.util.Map.of("id", 42));
    ForgeOpsTracker.resetForTesting();

    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    CountDownLatch received = new CountDownLatch(1);
    AtomicReference<String> body = new AtomicReference<>();
    server.createContext("/api/v1/events", exchange -> {
      body.set(new String(exchange.getRequestBody().readAllBytes()));
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      received.countDown();
    });
    server.start();
    try {
      ForgeOpsTracker.init(config -> {
        config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
        config.setEnvironment("production");
      }, false);

      ForgeOpsTracker.captureException(new RuntimeException("boom"));

      assertTrue(received.await(2, TimeUnit.SECONDS), "event was never delivered");
      assertFalse(body.get().contains("\"user\""), "the user set before resetForTesting should not have survived it");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void installedUncaughtExceptionHandlerChainsToAnyPreviousOne() {
    AtomicReference<Throwable> seenByPrevious = new AtomicReference<>();
    Thread.UncaughtExceptionHandler previous = (thread, error) -> seenByPrevious.set(error);
    Thread.setDefaultUncaughtExceptionHandler(previous);

    ForgeOpsTracker.init(config -> config.setDsn(null), true);

    Thread.UncaughtExceptionHandler installed = Thread.getDefaultUncaughtExceptionHandler();
    assertNotSame(previous, installed);

    RuntimeException error = new RuntimeException("uncaught");
    // Verified directly (not assumed) against a real uncaught throw before writing this test:
    // installing a default handler doesn't change whether/how the JVM exits, unlike Node's
    // uncaughtException: so there's no equivalent hazard to guard against here, only the
    // chaining contract itself to verify.
    assertDoesNotThrow(() -> installed.uncaughtException(Thread.currentThread(), error));
    assertSame(error, seenByPrevious.get());
  }

  @Test
  void installingTwiceDoesNotChainTheHandlerToItself() {
    ForgeOpsTracker.init(config -> config.setDsn(null), true);
    Thread.UncaughtExceptionHandler firstInstall = Thread.getDefaultUncaughtExceptionHandler();

    ForgeOpsTracker.init(config -> config.setDsn(null), true);
    Thread.UncaughtExceptionHandler secondInstall = Thread.getDefaultUncaughtExceptionHandler();

    assertSame(firstInstall, secondInstall);
  }

  // --- breadcrumbs -------------------------------------------------------------------------

  @Test
  void addBreadcrumbLazilyCreatesABufferAndAttachesItToTheNextCaptureExceptionCall()
      throws IOException, InterruptedException {
    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    CountDownLatch received = new CountDownLatch(1);
    AtomicReference<String> body = new AtomicReference<>();
    server.createContext("/api/v1/events", exchange -> {
      body.set(new String(exchange.getRequestBody().readAllBytes()));
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      received.countDown();
    });
    server.start();
    try {
      // Standalone, exactly like a plain main() or a background thread: no
      // ForgeOpsTrackerBreadcrumbContextFilter ever ran on this thread at all.
      ForgeOpsTracker.init(config -> {
        config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
        config.setEnvironment("production");
      }, false);

      ForgeOpsTracker.addBreadcrumb("loaded order", "billing", "info", Map.of("order_id", 7));
      ForgeOpsTracker.captureException(new RuntimeException("boom"));

      assertTrue(received.await(2, TimeUnit.SECONDS), "event was never delivered");
      assertTrue(body.get().contains("\"category\":\"billing\""));
      assertTrue(body.get().contains("\"message\":\"loaded order\""));
      assertTrue(body.get().contains("\"order_id\":7"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void addBreadcrumbCascadingOverloadsFillInSensibleDefaults() throws IOException, InterruptedException {
    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    CountDownLatch received = new CountDownLatch(1);
    AtomicReference<String> body = new AtomicReference<>();
    server.createContext("/api/v1/events", exchange -> {
      body.set(new String(exchange.getRequestBody().readAllBytes()));
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      received.countDown();
    });
    server.start();
    try {
      ForgeOpsTracker.init(config -> {
        config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
        config.setEnvironment("production");
      }, false);

      ForgeOpsTracker.addBreadcrumb("checkpoint");
      ForgeOpsTracker.captureException(new RuntimeException("boom"));

      assertTrue(received.await(2, TimeUnit.SECONDS), "event was never delivered");
      assertTrue(body.get().contains("\"category\":\"custom\""));
      assertTrue(body.get().contains("\"level\":\"info\""));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void addBreadcrumbWorksRegardlessOfTrackBreadcrumbsSinceOnlyTheAutomaticSourcesAreGated()
      throws IOException, InterruptedException {
    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    CountDownLatch received = new CountDownLatch(1);
    AtomicReference<String> body = new AtomicReference<>();
    server.createContext("/api/v1/events", exchange -> {
      body.set(new String(exchange.getRequestBody().readAllBytes()));
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      received.countDown();
    });
    server.start();
    try {
      ForgeOpsTracker.init(config -> {
        config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
        config.setEnvironment("production");
        config.setTrackBreadcrumbs(false);
      }, false);

      ForgeOpsTracker.addBreadcrumb("hand-added anyway");
      ForgeOpsTracker.captureException(new RuntimeException("boom"));

      assertTrue(received.await(2, TimeUnit.SECONDS), "event was never delivered");
      assertTrue(body.get().contains("hand-added anyway"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void recordBreadcrumbDoesNothingWhenTrackBreadcrumbsIsOff() throws IOException, InterruptedException {
    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    CountDownLatch received = new CountDownLatch(1);
    AtomicReference<String> body = new AtomicReference<>();
    server.createContext("/api/v1/events", exchange -> {
      body.set(new String(exchange.getRequestBody().readAllBytes()));
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      received.countDown();
    });
    server.start();
    try {
      ForgeOpsTracker.init(config -> {
        config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
        config.setEnvironment("production");
        config.setTrackBreadcrumbs(false);
      }, false);

      ForgeOpsTracker.recordBreadcrumb("controller", "GET /orders", "info", Map.of());
      ForgeOpsTracker.captureException(new RuntimeException("boom"));

      assertTrue(received.await(2, TimeUnit.SECONDS), "event was never delivered");
      assertFalse(body.get().contains("GET /orders"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void beginBreadcrumbTrailGivesEachThreadItsOwnFreshBufferOnSubsequentCaptures()
      throws IOException, InterruptedException {
    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    CountDownLatch received = new CountDownLatch(2);
    List<String> bodies = Collections.synchronizedList(new ArrayList<>());
    server.createContext("/api/v1/events", exchange -> {
      bodies.add(new String(exchange.getRequestBody().readAllBytes()));
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      received.countDown();
    });
    server.start();
    try {
      ForgeOpsTracker.init(config -> {
        config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
        config.setEnvironment("production");
      }, false);

      // Simulates two sequential requests handled on this same thread, the classic
      // ThreadLocal-leak scenario a reused Tomcat/Jetty worker thread creates.
      ForgeOpsTracker.beginBreadcrumbTrail();
      ForgeOpsTracker.addBreadcrumb("first request's own breadcrumb");
      ForgeOpsTracker.captureException(new RuntimeException("first request's error"));
      ForgeOpsTracker.endBreadcrumbTrail();

      ForgeOpsTracker.beginBreadcrumbTrail();
      ForgeOpsTracker.captureException(new RuntimeException("second request's error"));
      ForgeOpsTracker.endBreadcrumbTrail();

      assertTrue(received.await(2, TimeUnit.SECONDS), "events were never delivered");
      assertTrue(bodies.get(0).contains("first request's own breadcrumb"));
      assertFalse(bodies.get(1).contains("first request's own breadcrumb"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void resetForTestingClearsTheBreadcrumbTrail() throws IOException, InterruptedException {
    ForgeOpsTracker.addBreadcrumb("leftover from a previous test");
    ForgeOpsTracker.resetForTesting();

    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    CountDownLatch received = new CountDownLatch(1);
    AtomicReference<String> body = new AtomicReference<>();
    server.createContext("/api/v1/events", exchange -> {
      body.set(new String(exchange.getRequestBody().readAllBytes()));
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      received.countDown();
    });
    server.start();
    try {
      ForgeOpsTracker.init(config -> {
        config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
        config.setEnvironment("production");
      }, false);

      ForgeOpsTracker.captureException(new RuntimeException("boom"));

      assertTrue(received.await(2, TimeUnit.SECONDS), "event was never delivered");
      assertFalse(body.get().contains("leftover from a previous test"));
    } finally {
      server.stop(0);
    }
  }
}
