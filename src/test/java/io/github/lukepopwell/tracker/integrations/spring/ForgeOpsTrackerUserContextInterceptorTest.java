package io.github.lukepopwell.tracker.integrations.spring;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.github.lukepopwell.tracker.ForgeOpsTracker;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ForgeOpsTrackerUserContextInterceptorTest {
  private HttpServer server;
  private final ForgeOpsTrackerUserContextInterceptor interceptor = new ForgeOpsTrackerUserContextInterceptor();
  private final ForgeOpsTrackerHandlerExceptionResolver resolver = new ForgeOpsTrackerHandlerExceptionResolver();

  @BeforeEach
  void reset() {
    ForgeOpsTracker.resetForTesting();
    SecurityContextHolder.clearContext();
  }

  @AfterEach
  void cleanup() {
    ForgeOpsTracker.resetForTesting();
    SecurityContextHolder.clearContext();
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void attachesTheAuthenticatedUserToAReportedExceptionNeverScrubbedEvenThoughItsAnEmail() throws Exception {
    AtomicReference<String> body = deliverEventsTo();

    class FakePrincipal implements Principal {
      @Override
      public String getName() {
        return "ada";
      }

      public String getId() {
        return "42";
      }

      public String getEmail() {
        return "ada@example.com";
      }
    }

    List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(new FakePrincipal(), "n/a", authorities));

    HttpServletRequest request = fakeRequest();
    HttpServletResponse response = mock(HttpServletResponse.class);

    assertTrue(interceptor.preHandle(request, response, new Object()));
    resolver.resolveException(request, response, new Object(), new RuntimeException("boom"));
    interceptor.afterCompletion(request, response, new Object(), null);

    waitForDelivery(body);
    assertTrue(body.get().contains("\"id\":\"42\""));
    assertTrue(body.get().contains("ada@example.com"));
    assertTrue(body.get().contains("\"username\":\"ada\""));
  }

  @Test
  void doesNotAttachAUserForAnAnonymousAuthenticationTokenEvenThoughIsAuthenticatedIsTrue() throws Exception {
    AtomicReference<String> body = deliverEventsTo();

    // Confirmed directly (see the class's own doc comment): an AnonymousAuthenticationToken's own
    // isAuthenticated() returns true, so this specifically exercises the instanceof exclusion,
    // not just an isAuthenticated() check.
    List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"));
    SecurityContextHolder.getContext()
        .setAuthentication(new AnonymousAuthenticationToken("key", "anonymousUser", authorities));

    HttpServletRequest request = fakeRequest();
    HttpServletResponse response = mock(HttpServletResponse.class);

    interceptor.preHandle(request, response, new Object());
    resolver.resolveException(request, response, new Object(), new RuntimeException("boom"));

    waitForDelivery(body);
    assertFalse(body.get().contains("\"user\""));
  }

  @Test
  void doesNotAttachAUserWhenNoAuthenticationIsPresentAtAll() throws Exception {
    AtomicReference<String> body = deliverEventsTo();
    // SecurityContextHolder.clearContext() in @BeforeEach already leaves getAuthentication()
    // null: the same state an app with no Spring Security configured at all would have.

    HttpServletRequest request = fakeRequest();
    HttpServletResponse response = mock(HttpServletResponse.class);

    interceptor.preHandle(request, response, new Object());
    resolver.resolveException(request, response, new Object(), new RuntimeException("boom"));

    waitForDelivery(body);
    assertFalse(body.get().contains("\"user\""));
  }

  @Test
  void clearsTheUserInAfterCompletionEvenAfterAnAuthenticatedRequest() throws Exception {
    AtomicReference<String> body = deliverEventsTo();

    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                (Principal) () -> "ada", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));

    HttpServletRequest request = fakeRequest();
    HttpServletResponse response = mock(HttpServletResponse.class);
    interceptor.preHandle(request, response, new Object());
    interceptor.afterCompletion(request, response, new Object(), null);

    // A later report on this same thread, with no interceptor run in between at all (a background
    // task, say), must never see the previous request's user leak into it.
    ForgeOpsTracker.captureException(new RuntimeException("later, unrelated"));

    waitForDelivery(body);
    assertFalse(body.get().contains("\"user\""));
  }

  private HttpServletRequest fakeRequest() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("POST");
    when(request.getRequestURI()).thenReturn("/orders");
    return request;
  }

  private AtomicReference<String> deliverEventsTo() throws Exception {
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
