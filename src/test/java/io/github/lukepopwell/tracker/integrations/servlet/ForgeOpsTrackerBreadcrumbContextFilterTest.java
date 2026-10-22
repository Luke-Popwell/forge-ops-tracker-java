package io.github.lukepopwell.tracker.integrations.servlet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.github.lukepopwell.tracker.Configuration;
import io.github.lukepopwell.tracker.ForgeOpsTracker;
import io.github.lukepopwell.tracker.integrations.spring.ForgeOpsTrackerHandlerExceptionResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ForgeOpsTrackerBreadcrumbContextFilterTest {
  private HttpServer server;
  private final ForgeOpsTrackerBreadcrumbContextFilter filter = new ForgeOpsTrackerBreadcrumbContextFilter();

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
  void aBreadcrumbAddedDuringTheChainIsAttachedToAnExceptionCapturedLaterInThatSameChain() throws Exception {
    AtomicReference<String> body = deliverEventsTo();
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    doAnswer(invocation -> {
      ForgeOpsTracker.addBreadcrumb("loaded order 42", "billing");
      ForgeOpsTracker.captureException(new RuntimeException("boom"));
      return null;
    }).when(chain).doFilter(any(), any());

    filter.doFilter(request, response, chain);

    waitForDelivery(body);
    assertTrue(body.get().contains("loaded order 42"));
  }

  @Test
  void givesEachCallItsOwnFreshBufferNotSharedAcrossRequestsOnTheSameThread() throws Exception {
    AtomicReference<String> body = deliverEventsTo();
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    FilterChain firstChain = mock(FilterChain.class);
    doAnswer(invocation -> {
      ForgeOpsTracker.addBreadcrumb("first request's own breadcrumb");
      return null;
    }).when(firstChain).doFilter(any(), any());
    filter.doFilter(request, response, firstChain);

    FilterChain secondChain = mock(FilterChain.class);
    doAnswer(invocation -> {
      ForgeOpsTracker.captureException(new RuntimeException("second request's error"));
      return null;
    }).when(secondChain).doFilter(any(), any());
    filter.doFilter(request, response, secondChain);

    waitForDelivery(body);
    assertFalse(body.get().contains("first request's own breadcrumb"));
  }

  @Test
  void clearsTheTrailEvenWhenTheChainThrows() throws Exception {
    AtomicReference<String> body = deliverEventsTo();
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    RuntimeException failure = new RuntimeException("handler blew up");
    doAnswer(invocation -> {
      ForgeOpsTracker.addBreadcrumb("added before the throw");
      throw failure;
    }).when(chain).doFilter(any(), any());

    assertThrows(RuntimeException.class, () -> filter.doFilter(request, response, chain));

    // A later, unrelated capture on this same thread (a background task, say, with no filter
    // wrapping it at all) must never see the previous request's trail leak into it.
    ForgeOpsTracker.captureException(new RuntimeException("later, unrelated"));

    waitForDelivery(body);
    assertFalse(body.get().contains("added before the throw"));
  }

  @Test
  void addBreadcrumbStillWorksStandaloneWhenTrackBreadcrumbsIsOffSinceOnlyTheFilterStartIsGated() throws Exception {
    AtomicReference<String> body = deliverEventsTo(config -> config.setTrackBreadcrumbs(false));
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    doAnswer(invocation -> {
      // beginBreadcrumbTrail() itself is a no-op with the flag off (see its own Javadoc), but
      // addBreadcrumb lazily starts its own buffer regardless, the same "manual API always
      // works" contract ForgeOpsTrackerTest already covers standalone; this proves it holds
      // inside a request wrapped by this filter too.
      ForgeOpsTracker.addBreadcrumb("added by hand despite the flag");
      ForgeOpsTracker.captureException(new RuntimeException("boom"));
      return null;
    }).when(chain).doFilter(any(), any());

    filter.doFilter(request, response, chain);

    waitForDelivery(body);
    assertTrue(body.get().contains("added by hand despite the flag"));
  }

  /**
   * Stands in for Spring's own exception resolution: {@code DispatcherServlet.doDispatch} always
   * invokes a {@code HandlerExceptionResolver} chain from inside its own dispatch, which is
   * itself always invoked from inside this filter's {@code chain.doFilter} (see this filter's own
   * Javadoc for why that means no separate Spring-specific breadcrumb-scoping mechanism is
   * needed). Calling {@link ForgeOpsTrackerHandlerExceptionResolver} directly here, the same way
   * {@code ForgeOpsTrackerUserContextInterceptorTest} already calls it directly rather than
   * standing up a real {@code DispatcherServlet}, confirms the breadcrumb added earlier in this
   * simulated dispatch is visible to it purely because both ran on the same thread inside the
   * same {@code chain.doFilter} call, with no Spring-specific wiring involved at all.
   */
  @Test
  void aBreadcrumbAddedDuringASimulatedSpringDispatchReachesTheHandlerExceptionResolver() throws Exception {
    AtomicReference<String> body = deliverEventsTo();
    ForgeOpsTrackerHandlerExceptionResolver resolver = new ForgeOpsTrackerHandlerExceptionResolver();
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("POST");
    when(request.getRequestURI()).thenReturn("/orders");
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    doAnswer(invocation -> {
      // Standing in for a Spring interceptor/service layer adding context mid-dispatch.
      ForgeOpsTracker.addBreadcrumb("validated payment method", "billing", "info", Map.of("method", "card"));
      // Standing in for DispatcherServlet.doDispatch resolving a handler's thrown exception.
      resolver.resolveException(request, response, new Object(), new RuntimeException("card declined"));
      return null;
    }).when(chain).doFilter(any(), any());

    filter.doFilter(request, response, chain);

    waitForDelivery(body);
    assertTrue(body.get().contains("validated payment method"));
    assertTrue(body.get().contains("\"method\":\"card\""));
  }

  private AtomicReference<String> deliverEventsTo() throws Exception {
    return deliverEventsTo(config -> { });
  }

  private AtomicReference<String> deliverEventsTo(Consumer<Configuration> extraConfig) throws Exception {
    AtomicReference<String> body = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/api/v1/events", exchange -> {
      body.set(new String(exchange.getRequestBody().readAllBytes()));
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
    });
    server.start();

    ForgeOpsTracker.init(config -> {
      config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
      config.setEnvironment("production");
      extraConfig.accept(config);
    }, false);

    return body;
  }

  private void waitForDelivery(AtomicReference<String> body) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 2000;
    while (body.get() == null && System.currentTimeMillis() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(body.get() != null, "event was never delivered");
  }
}
