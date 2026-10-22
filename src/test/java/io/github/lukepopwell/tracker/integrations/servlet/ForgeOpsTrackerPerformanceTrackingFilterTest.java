package io.github.lukepopwell.tracker.integrations.servlet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.github.lukepopwell.tracker.ForgeOpsTracker;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.HandlerMapping;

class ForgeOpsTrackerPerformanceTrackingFilterTest {
  private HttpServer server;

  @BeforeEach
  void reset() {
    ForgeOpsTracker.resetForTesting();
  }

  @AfterEach
  void cleanup() {
    ForgeOpsTracker.resetForTesting();
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void recordsTheRequestsOwnDuration() throws Exception {
    CountDownLatch received = new CountDownLatch(1);
    AtomicReference<String> body = new AtomicReference<>();
    startServerCapturing(received, body);
    init();

    ForgeOpsTrackerPerformanceTrackingFilter filter = new ForgeOpsTrackerPerformanceTrackingFilter();
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getRequestURI()).thenReturn("/ok");
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertTrue(received.await(3, TimeUnit.SECONDS), "performance sample was never delivered");
    assertTrue(body.get().contains("\"transaction_name\":\"GET /ok\""));
    assertTrue(body.get().contains("\"request_count\":1"));
  }

  @Test
  void usesTheSpringMatchedPatternWhenPresentNotTheRawUri() throws Exception {
    CountDownLatch received = new CountDownLatch(1);
    AtomicReference<String> body = new AtomicReference<>();
    startServerCapturing(received, body);
    init();

    ForgeOpsTrackerPerformanceTrackingFilter filter = new ForgeOpsTrackerPerformanceTrackingFilter();
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getRequestURI()).thenReturn("/users/42");
    // Set directly, the same attribute Spring's own HandlerMapping sets once a handler
    // actually matches: exercising the filter directly (see its own Javadoc) never boots a
    // real DispatcherServlet.
    when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)).thenReturn("/users/{id}");
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertTrue(received.await(3, TimeUnit.SECONDS), "performance sample was never delivered");
    // The route pattern, not "GET /users/42": a distinct user id must not explode into its own
    // separate transaction the way the literal URI would.
    assertTrue(body.get().contains("\"transaction_name\":\"GET /users/{id}\""));
  }

  @Test
  void fallsBackToTheRawUriWhenNoSpringPatternIsSet() throws Exception {
    CountDownLatch received = new CountDownLatch(1);
    AtomicReference<String> body = new AtomicReference<>();
    startServerCapturing(received, body);
    init();

    ForgeOpsTrackerPerformanceTrackingFilter filter = new ForgeOpsTrackerPerformanceTrackingFilter();
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getRequestURI()).thenReturn("/nowhere");
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertTrue(received.await(3, TimeUnit.SECONDS), "performance sample was never delivered");
    assertTrue(body.get().contains("\"transaction_name\":\"GET /nowhere\""));
  }

  @Test
  void stillRecordsAndRethrowsUnchangedWhenTheChainThrows() throws Exception {
    CountDownLatch received = new CountDownLatch(1);
    AtomicReference<String> body = new AtomicReference<>();
    startServerCapturing(received, body);
    init();

    ForgeOpsTrackerPerformanceTrackingFilter filter = new ForgeOpsTrackerPerformanceTrackingFilter();
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getRequestURI()).thenReturn("/throw");
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    RuntimeException failure = new RuntimeException("handler blew up");
    doThrow(failure).when(chain).doFilter(any(), any());

    assertThrows(RuntimeException.class, () -> filter.doFilter(request, response, chain));

    assertTrue(received.await(3, TimeUnit.SECONDS), "performance sample was never delivered");
    assertTrue(body.get().contains("\"transaction_name\":\"GET /throw\""));
  }

  @Test
  void recordsAnAutomaticControllerBreadcrumbIntoTheCurrentThreadsOwnTrail() throws Exception {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    AtomicReference<String> eventBody = new AtomicReference<>();
    CountDownLatch eventReceived = new CountDownLatch(1);
    server.createContext("/api/v1/performance_samples", exchange -> {
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
    });
    server.createContext("/api/v1/events", exchange -> {
      eventBody.set(new String(exchange.getRequestBody().readAllBytes()));
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      eventReceived.countDown();
    });
    server.start();
    init();

    ForgeOpsTrackerPerformanceTrackingFilter filter = new ForgeOpsTrackerPerformanceTrackingFilter();
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getRequestURI()).thenReturn("/ok");
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getStatus()).thenReturn(200);
    FilterChain chain = mock(FilterChain.class);

    // Started here (standing in for ForgeOpsTrackerBreadcrumbContextFilter, an independent
    // filter this test deliberately exercises this filter without, matching the "order doesn't
    // matter, exercise directly" approach every other test in this class already uses) so there's
    // a trail for the automatic breadcrumb below to land in.
    ForgeOpsTracker.beginBreadcrumbTrail();
    filter.doFilter(request, response, chain);
    // Reported only now: this documents the same gap sdks/python's Django/Flask integrations and
    // sdks/php's Symfony integration both have, and this filter's own doc comment explains why
    // (recorded in this filter's own finally, which only runs after ForgeOpsTrackerFilter would
    // already have reported an exception that actually propagated).
    ForgeOpsTracker.captureException(new RuntimeException("unrelated later error"));

    assertTrue(eventReceived.await(3, TimeUnit.SECONDS), "event was never delivered");
    assertTrue(eventBody.get().contains("\"category\":\"controller\""));
    assertTrue(eventBody.get().contains("\"message\":\"GET /ok\""));
    assertTrue(eventBody.get().contains("\"path\":\"/ok\""));
    assertTrue(eventBody.get().contains("\"level\":\"info\""));
  }

  @Test
  void recordsTheAutomaticControllerBreadcrumbAtErrorLevelForA5xxResponse() throws Exception {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    AtomicReference<String> eventBody = new AtomicReference<>();
    CountDownLatch eventReceived = new CountDownLatch(1);
    server.createContext("/api/v1/performance_samples", exchange -> {
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
    });
    server.createContext("/api/v1/events", exchange -> {
      eventBody.set(new String(exchange.getRequestBody().readAllBytes()));
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      eventReceived.countDown();
    });
    server.start();
    init();

    ForgeOpsTrackerPerformanceTrackingFilter filter = new ForgeOpsTrackerPerformanceTrackingFilter();
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getRequestURI()).thenReturn("/broken");
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getStatus()).thenReturn(503);
    FilterChain chain = mock(FilterChain.class);

    ForgeOpsTracker.beginBreadcrumbTrail();
    filter.doFilter(request, response, chain);
    ForgeOpsTracker.captureException(new RuntimeException("unrelated later error"));

    assertTrue(eventReceived.await(3, TimeUnit.SECONDS), "event was never delivered");
    assertTrue(eventBody.get().contains("\"level\":\"error\""));
    assertTrue(eventBody.get().contains("\"status\":503"));
  }

  @Test
  void recordsNoAutomaticBreadcrumbAtAllWhenTrackBreadcrumbsIsOff() throws Exception {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    AtomicReference<String> eventBody = new AtomicReference<>();
    CountDownLatch eventReceived = new CountDownLatch(1);
    server.createContext("/api/v1/performance_samples", exchange -> {
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
    });
    server.createContext("/api/v1/events", exchange -> {
      eventBody.set(new String(exchange.getRequestBody().readAllBytes()));
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      eventReceived.countDown();
    });
    server.start();
    ForgeOpsTracker.init(config -> {
      config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
      config.setEnvironment("production");
      config.setPerformanceFlushInterval(Duration.ofMillis(1));
      config.setTrackBreadcrumbs(false);
    }, false);

    ForgeOpsTrackerPerformanceTrackingFilter filter = new ForgeOpsTrackerPerformanceTrackingFilter();
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getRequestURI()).thenReturn("/ok");
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getStatus()).thenReturn(200);
    FilterChain chain = mock(FilterChain.class);

    ForgeOpsTracker.beginBreadcrumbTrail(); // itself a no-op with the flag off
    filter.doFilter(request, response, chain);
    ForgeOpsTracker.addBreadcrumb("manually added, should still show up");
    ForgeOpsTracker.captureException(new RuntimeException("boom"));

    assertTrue(eventReceived.await(3, TimeUnit.SECONDS), "event was never delivered");
    // The manual breadcrumb still comes through (trackBreadcrumbs only gates the automatic
    // controller source): its presence, alongside the automatic one's absence, is what actually
    // proves this filter's own automatic recording was skipped, not just that nothing at all
    // came through.
    assertTrue(eventBody.get().contains("manually added, should still show up"));
    assertFalse(eventBody.get().contains("\"category\":\"controller\""));
  }

  @Test
  void aSlowRequestReportsItsTraceWithTheRequestAsRootSpan() throws Exception {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    CountDownLatch spansReceived = new CountDownLatch(1);
    AtomicReference<String> spansBody = new AtomicReference<>();
    server.createContext("/api/v1/performance_samples", exchange -> {
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
    });
    server.createContext("/api/v1/spans", exchange -> {
      spansBody.set(new String(exchange.getRequestBody().readAllBytes()));
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      spansReceived.countDown();
    });
    server.start();
    ForgeOpsTracker.init(config -> {
      config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
      config.setEnvironment("production");
      config.setTraceCaptureThreshold(Duration.ofMillis(20));
    }, false);

    ForgeOpsTrackerPerformanceTrackingFilter filter = new ForgeOpsTrackerPerformanceTrackingFilter();
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getRequestURI()).thenReturn("/slow");
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    doAnswer(invocation -> {
      ForgeOpsTracker.span("inner work", () -> {
        Thread.sleep(40);
        return null;
      });
      return null;
    }).when(chain).doFilter(any(), any());

    filter.doFilter(request, response, chain);

    assertTrue(spansReceived.await(3, TimeUnit.SECONDS), "trace was never delivered");
    assertTrue(spansBody.get().contains("\"name\":\"GET /slow\""));
    assertTrue(spansBody.get().contains("\"name\":\"inner work\""));
  }

  private void init() {
    ForgeOpsTracker.init(config -> {
      config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
      config.setEnvironment("production");
      config.setPerformanceFlushInterval(Duration.ofMillis(1)); // floored to 1s by PerformanceFlusher itself
    }, false);
  }

  private void startServerCapturing(CountDownLatch received, AtomicReference<String> body) throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/api/v1/performance_samples", exchange -> {
      body.set(new String(exchange.getRequestBody().readAllBytes()));
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      received.countDown();
    });
    server.start();
  }
}
