package io.github.lukepopwell.tracker.integrations.servlet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

class ForgeOpsTrackerSessionTrackingFilterTest {
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
  void countsASuccessfulRequestAsACrashFreeSession() throws Exception {
    CountDownLatch received = new CountDownLatch(1);
    AtomicReference<String> body = new AtomicReference<>();
    startServerCapturing(received, body);
    init();

    ForgeOpsTrackerSessionTrackingFilter filter = new ForgeOpsTrackerSessionTrackingFilter();
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getStatus()).thenReturn(200);
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertTrue(received.await(3, TimeUnit.SECONDS), "session checkin was never delivered");
    assertEquals(true, body.get().contains("\"sessions_count\":1"));
    assertEquals(true, body.get().contains("\"crashed_sessions_count\":0"));
  }

  @Test
  void countsARequestThatThrowsAsACrashedSessionAndStillRethrowsUnchanged() throws Exception {
    CountDownLatch received = new CountDownLatch(1);
    AtomicReference<String> body = new AtomicReference<>();
    startServerCapturing(received, body);
    init();

    ForgeOpsTrackerSessionTrackingFilter filter = new ForgeOpsTrackerSessionTrackingFilter();
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    RuntimeException failure = new RuntimeException("handler blew up");
    doThrow(failure).when(chain).doFilter(any(), any());

    assertThrows(RuntimeException.class, () -> filter.doFilter(request, response, chain));

    assertTrue(received.await(3, TimeUnit.SECONDS), "session checkin was never delivered");
    assertEquals(true, body.get().contains("\"crashed_sessions_count\":1"));
  }

  @Test
  void countsA5xxResponseAsACrashedSessionEvenWithoutAThrownException() throws Exception {
    CountDownLatch received = new CountDownLatch(1);
    AtomicReference<String> body = new AtomicReference<>();
    startServerCapturing(received, body);
    init();

    // A Spring-Boot-style case: a controller's exception was already resolved into a response
    // (@ExceptionHandler/@ControllerAdvice) before it ever reached this filter as a thrown
    // exception; the response's own status is the only signal left.
    ForgeOpsTrackerSessionTrackingFilter filter = new ForgeOpsTrackerSessionTrackingFilter();
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getStatus()).thenReturn(500);
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertTrue(received.await(3, TimeUnit.SECONDS), "session checkin was never delivered");
    assertEquals(true, body.get().contains("\"crashed_sessions_count\":1"));
  }

  private void init() {
    ForgeOpsTracker.init(config -> {
      config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
      config.setEnvironment("production");
      config.setSessionFlushInterval(Duration.ofMillis(1)); // floored to 1s by SessionFlusher itself
    }, false);
  }

  private void startServerCapturing(CountDownLatch received, AtomicReference<String> body) throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/api/v1/session_checkins", exchange -> {
      body.set(new String(exchange.getRequestBody().readAllBytes()));
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      received.countDown();
    });
    server.start();
  }
}
