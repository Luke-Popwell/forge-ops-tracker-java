package io.github.lukepopwell.tracker.integrations.servlet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.lukepopwell.tracker.ForgeOpsTracker;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ForgeOpsTrackerFilterTest {
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
  void reportsAnExceptionThatPropagatesOutOfTheChainThenRethrowsItUnchanged() throws Exception {
    CountDownLatch received = new CountDownLatch(1);
    AtomicReference<String> body = new AtomicReference<>();
    startServerCapturing(received, body);

    ForgeOpsTracker.init(config -> {
      config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
      config.setEnvironment("production");
    }, false);

    ForgeOpsTrackerFilter filter = new ForgeOpsTrackerFilter();
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getRequestURI()).thenReturn("/widgets/1");
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    RuntimeException failure = new RuntimeException("handler blew up");
    doThrow(failure).when(chain).doFilter(request, response);

    RuntimeException rethrown = assertThrows(RuntimeException.class, () -> filter.doFilter(request, response, chain));

    // The exact same exception propagates out: the filter observes, it never replaces it,
    // exactly like the Express/Fastify integrations' "let the framework handle it exactly as if
    // this middleware weren't installed" contract.
    assertSame(failure, rethrown);
    assertTrue(received.await(2, TimeUnit.SECONDS), "event was never delivered");
    assertEquals(true, body.get().contains("handler blew up"));
  }

  @Test
  void unwrapsTheRootCauseOfAServletException() throws Exception {
    CountDownLatch received = new CountDownLatch(1);
    AtomicReference<String> body = new AtomicReference<>();
    startServerCapturing(received, body);

    ForgeOpsTracker.init(config -> {
      config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
      config.setEnvironment("production");
    }, false);

    ForgeOpsTrackerFilter filter = new ForgeOpsTrackerFilter();
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    RuntimeException rootCause = new RuntimeException("the real underlying failure");
    doThrow(new ServletException(rootCause)).when(chain).doFilter(any(), any());

    assertThrows(ServletException.class, () -> filter.doFilter(request, response, chain));

    assertTrue(received.await(2, TimeUnit.SECONDS), "event was never delivered");
    assertEquals(true, body.get().contains("the real underlying failure"));
  }

  private void startServerCapturing(CountDownLatch received, AtomicReference<String> body) throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/api/v1/events", exchange -> {
      body.set(new String(exchange.getRequestBody().readAllBytes()));
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      received.countDown();
    });
    server.start();
  }
}
