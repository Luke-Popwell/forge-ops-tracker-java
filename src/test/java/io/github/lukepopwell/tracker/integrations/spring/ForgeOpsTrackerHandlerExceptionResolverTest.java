package io.github.lukepopwell.tracker.integrations.spring;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.lukepopwell.tracker.ForgeOpsTracker;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.ModelAndView;

class ForgeOpsTrackerHandlerExceptionResolverTest {
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
  void reportsTheExceptionAndAlwaysReturnsNullSoSpringsOwnResolutionContinues() throws Exception {
    CountDownLatch received = new CountDownLatch(1);
    AtomicReference<String> body = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/api/v1/events", exchange -> {
      body.set(new String(exchange.getRequestBody().readAllBytes()));
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      received.countDown();
    });
    server.start();

    ForgeOpsTracker.init(config -> {
      config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
      config.setEnvironment("production");
    }, false);

    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("POST");
    when(request.getRequestURI()).thenReturn("/orders");
    HttpServletResponse response = mock(HttpServletResponse.class);

    ForgeOpsTrackerHandlerExceptionResolver resolver = new ForgeOpsTrackerHandlerExceptionResolver();
    ModelAndView result = resolver.resolveException(request, response, new Object(), new RuntimeException("controller blew up"));

    // Never claims to have handled it: Spring's own @ExceptionHandler / DefaultErrorAttributes
    // still runs exactly as if this resolver weren't registered.
    assertNull(result);
    assertTrue(received.await(2, TimeUnit.SECONDS), "event was never delivered");
    assertTrue(body.get().contains("controller blew up"));
  }

  @Test
  void runsAtHighestPrecedenceSoItSeesExceptionsBeforeAnyResolverThatWouldClaimThem() {
    ForgeOpsTrackerHandlerExceptionResolver resolver = new ForgeOpsTrackerHandlerExceptionResolver();

    assertTrue(resolver.getOrder() == Ordered.HIGHEST_PRECEDENCE);
  }
}
