package io.github.lukepopwell.tracker.integrations.servlet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.github.lukepopwell.tracker.Configuration;
import io.github.lukepopwell.tracker.ForgeOpsTracker;
import io.github.lukepopwell.tracker.integrations.spring.ForgeOpsTrackerHandlerExceptionResolver;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.MappingMatch;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The W3C trace context contract end to end through the servlet filters: one trace id per request
 * (continued from an incoming {@code traceparent}), on every error reported during it along with
 * its transaction name and endpoint, handed to outgoing calls by {@code ForgeOpsTracker.httpSpan},
 * and an errored request's trace always sent.
 */
class ForgeOpsTrackerTraceContextTest {
  private static final String INCOMING_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
  private static final String INCOMING_SPAN_ID = "00f067aa0ba902b7";
  private static final String INCOMING = "00-" + INCOMING_TRACE_ID + "-" + INCOMING_SPAN_ID + "-01";

  private HttpServer server;
  private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
  private final BlockingQueue<String> traces = new LinkedBlockingQueue<>();

  @BeforeEach
  void start() throws Exception {
    ForgeOpsTracker.resetForTesting();
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/api/v1/events", exchange -> {
      events.add(new String(exchange.getRequestBody().readAllBytes()));
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
    });
    server.createContext("/api/v1/spans", exchange -> {
      traces.add(new String(exchange.getRequestBody().readAllBytes()));
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
    });
    server.createContext("/", exchange -> {
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
    });
    server.start();
  }

  @AfterEach
  void stop() {
    ForgeOpsTracker.resetForTesting();
    server.stop(0);
  }

  private void init(Consumer<Configuration> extra) {
    ForgeOpsTracker.init(config -> {
      config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
      config.setEnvironment("production");
      // High enough that only an errored request's trace is ever sent, unless a test lowers it.
      config.setTraceCaptureThreshold(Duration.ofSeconds(30));
      extra.accept(config);
    }, false);
  }

  private void init() {
    init(config -> {});
  }

  /** A request mock whose attributes really store values, the way a container's request does. */
  private static HttpServletRequest request(String method, String uri, String traceparent) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    Map<String, Object> attributes = new ConcurrentHashMap<>();
    when(request.getMethod()).thenReturn(method);
    when(request.getRequestURI()).thenReturn(uri);
    when(request.getHeader("traceparent")).thenReturn(traceparent);
    when(request.getAttribute(anyString())).thenAnswer(call -> attributes.get(call.getArgument(0, String.class)));
    doAnswer(call -> {
      attributes.put(call.getArgument(0), call.getArgument(1));
      return null;
    }).when(request).setAttribute(anyString(), any());
    return request;
  }

  /** Spring's HandlerMapping sets this once a handler matches, partway into the request. */
  private static void matchRoute(Object request, String pattern) {
    ((HttpServletRequest) request).setAttribute(ForgeOpsTrackerRequestContext.SPRING_PATTERN_ATTRIBUTE, pattern);
  }

  private static FilterChain around(Filter inner, FilterChain innermost) {
    return (req, res) -> inner.doFilter(req, res, innermost);
  }

  private static String field(String json, String key) {
    Matcher match = Pattern.compile("\"" + key + "\":\"([^\"]*)\"").matcher(json);
    return match.find() ? match.group(1) : null;
  }

  /** The span with this name: {span_id, parent_span_id (or "null")}. */
  private static String[] span(String json, String name) {
    Matcher match = Pattern.compile(
        "\\{\"span_id\":\"([0-9a-f]{16})\",\"parent_span_id\":(?:\"([0-9a-f]{16})\"|null),\"name\":\""
            + Pattern.quote(name) + "\"").matcher(json);
    assertTrue(match.find(), "no span named " + name + " in " + json);
    return new String[] {match.group(1), match.group(2) == null ? "null" : match.group(2)};
  }

  @Test
  void anUnhandledErrorCarriesTheIncomingTraceIdTransactionNameAndEndpointAndSendsTheFastTrace() throws Exception {
    init();
    HttpServletRequest request = request("POST", "/orders/42", INCOMING);
    HttpServletResponse response = mock(HttpServletResponse.class);
    RuntimeException failure = new RuntimeException("checkout failed");
    FilterChain chain = around(new ForgeOpsTrackerFilter(), (req, res) -> {
      matchRoute(req, "/orders/{id}");
      throw failure;
    });

    RuntimeException thrown = assertThrows(RuntimeException.class,
        () -> new ForgeOpsTrackerPerformanceTrackingFilter().doFilter(request, response, chain));
    assertEquals(failure, thrown);

    String event = events.poll(3, TimeUnit.SECONDS);
    assertNotNull(event, "event was never delivered");
    assertEquals(INCOMING_TRACE_ID, field(event, "trace_id"));
    assertEquals("POST /orders/{id}", field(event, "transaction_name"));
    assertEquals("POST /orders/{id}", field(event, "endpoint"));

    // Well under traceCaptureThreshold, but it errored, so its trace is sent anyway, nested under
    // the caller's span.
    String trace = traces.poll(3, TimeUnit.SECONDS);
    assertNotNull(trace, "an errored request's trace was never delivered");
    assertEquals(INCOMING_TRACE_ID, field(trace, "trace_id"));
    assertEquals(INCOMING_SPAN_ID, span(trace, "POST /orders/{id}")[1]);
    assertNull(ForgeOpsTracker.currentRequestState());
  }

  @Test
  void aHandledErrorReportedMidRequestAlreadyHasItsEndpointAndStillSendsTheTrace() throws Exception {
    init();
    HttpServletRequest request = request("GET", "/users/7", null);
    AtomicReference<String> traceIdInside = new AtomicReference<>();
    new ForgeOpsTrackerPerformanceTrackingFilter().doFilter(request, mock(HttpServletResponse.class), (req, res) -> {
      matchRoute(req, "/users/{id}");
      traceIdInside.set(ForgeOpsTracker.currentTraceId());
      ForgeOpsTracker.captureException(new IllegalStateException("handled"));
    });

    String event = events.poll(3, TimeUnit.SECONDS);
    assertNotNull(event);
    assertTrue(traceIdInside.get().matches("[0-9a-f]{32}"));
    assertEquals(traceIdInside.get(), field(event, "trace_id"));
    assertEquals("GET /users/{id}", field(event, "endpoint"));
    assertEquals("GET /users/{id}", field(event, "transaction_name"));

    String trace = traces.poll(3, TimeUnit.SECONDS);
    assertNotNull(trace, "a request with a reported error must send its trace");
    assertEquals(traceIdInside.get(), field(trace, "trace_id"));
    assertEquals("null", span(trace, "GET /users/{id}")[1]);
  }

  @Test
  void aFastSuccessfulRequestStillSendsNothing() throws Exception {
    init();
    new ForgeOpsTrackerPerformanceTrackingFilter()
        .doFilter(request("GET", "/ok", INCOMING), mock(HttpServletResponse.class), (req, res) -> {});

    assertNull(traces.poll(300, TimeUnit.MILLISECONDS));
  }

  @Test
  void aMalformedTraceparentStartsAFreshTrace() throws Exception {
    init();
    AtomicReference<String> traceId = new AtomicReference<>();
    new ForgeOpsTrackerFilter().doFilter(request("GET", "/x", "00-" + INCOMING_TRACE_ID.toUpperCase() + "-"
        + INCOMING_SPAN_ID + "-01"), mock(HttpServletResponse.class),
        (req, res) -> traceId.set(ForgeOpsTracker.currentTraceId()));

    assertTrue(traceId.get().matches("[0-9a-f]{32}"));
    assertNotEquals(INCOMING_TRACE_ID, traceId.get());
  }

  @Test
  void withTrackTracingOffErrorsStillCarryTheTraceIdButNoTraceIsSent() throws Exception {
    init(config -> config.setTrackTracing(false));
    HttpServletRequest request = request("GET", "/a", INCOMING);
    FilterChain chain = around(new ForgeOpsTrackerFilter(), (req, res) -> {
      throw new RuntimeException("boom");
    });

    assertThrows(RuntimeException.class,
        () -> new ForgeOpsTrackerPerformanceTrackingFilter().doFilter(request, mock(HttpServletResponse.class), chain));

    String event = events.poll(3, TimeUnit.SECONDS);
    assertNotNull(event);
    assertEquals(INCOMING_TRACE_ID, field(event, "trace_id"));
    assertNull(traces.poll(300, TimeUnit.MILLISECONDS));
  }

  @Test
  void theErrorFilterRegisteredOutsideTheOthersStillReportsTheRequestContext() throws Exception {
    // The reverse of the recommended order: ForgeOpsTrackerFilter outermost, so every other
    // filter's finally has already run by the time it reports. The request state is owned by the
    // outermost ForgeOps filter, so it's still published when the report happens.
    init();
    HttpServletRequest request = request("DELETE", "/carts/9", INCOMING);
    FilterChain chain = around(new ForgeOpsTrackerPerformanceTrackingFilter(),
        around(new ForgeOpsTrackerBreadcrumbContextFilter(), (req, res) -> {
          matchRoute(req, "/carts/{id}");
          throw new IllegalArgumentException("bad cart");
        }));

    assertThrows(IllegalArgumentException.class,
        () -> new ForgeOpsTrackerFilter().doFilter(request, mock(HttpServletResponse.class), chain));

    String event = events.poll(3, TimeUnit.SECONDS);
    assertNotNull(event);
    assertEquals(INCOMING_TRACE_ID, field(event, "trace_id"));
    assertEquals("DELETE /carts/{id}", field(event, "endpoint"));
    assertNotNull(traces.poll(3, TimeUnit.SECONDS), "the errored request's trace was never delivered");
    assertNull(ForgeOpsTracker.currentRequestState());
  }

  @Test
  void aSecondDispatchOfTheSameRequestKeepsItsTraceId() throws Exception {
    init();
    HttpServletRequest request = request("GET", "/x", null);
    AtomicReference<String> first = new AtomicReference<>();
    AtomicReference<String> second = new AtomicReference<>();
    ForgeOpsTrackerFilter filter = new ForgeOpsTrackerFilter();

    filter.doFilter(request, mock(HttpServletResponse.class), (req, res) -> first.set(ForgeOpsTracker.currentTraceId()));
    assertNull(ForgeOpsTracker.currentTraceId());
    filter.doFilter(request, mock(HttpServletResponse.class), (req, res) -> second.set(ForgeOpsTracker.currentTraceId()));

    assertNotNull(first.get());
    assertEquals(first.get(), second.get());
  }

  @Test
  void theSpringResolverOnItsOwnStillReportsTheTraceIdAndEndpoint() throws Exception {
    init();
    HttpServletRequest request = request("PUT", "/items/3", INCOMING);
    matchRoute(request, "/items/{id}");

    new ForgeOpsTrackerHandlerExceptionResolver()
        .resolveException(request, mock(HttpServletResponse.class), null, new IllegalStateException("resolved"));

    String event = events.poll(3, TimeUnit.SECONDS);
    assertNotNull(event);
    assertEquals(INCOMING_TRACE_ID, field(event, "trace_id"));
    assertEquals("PUT /items/{id}", field(event, "endpoint"));
    assertNull(ForgeOpsTracker.currentRequestState());
  }

  @Test
  void anErrorOutsideAnyRequestHasNoTraceFields() throws Exception {
    init();
    ForgeOpsTracker.captureException(new RuntimeException("background job"));

    String event = events.poll(3, TimeUnit.SECONDS);
    assertNotNull(event);
    assertFalse(event.contains("\"trace_id\""));
    assertFalse(event.contains("\"transaction_name\""));
    assertFalse(event.contains("\"endpoint\""));
  }

  @Test
  void nothingIsPublishedWhenTheClientIsNotEnabled() throws Exception {
    ForgeOpsTracker.init(config -> config.setDsn(null), false);
    AtomicReference<String> traceId = new AtomicReference<>("unset");
    new ForgeOpsTrackerFilter().doFilter(request("GET", "/x", INCOMING), mock(HttpServletResponse.class),
        (req, res) -> traceId.set(ForgeOpsTracker.currentTraceId()));

    assertNull(traceId.get());
  }

  @Test
  void httpSpanHandsOutATraceparentNamingItsOwnRecordedSpan() throws Exception {
    init(config -> config.setTraceCaptureThreshold(Duration.ZERO));
    AtomicReference<Map<String, String>> sent = new AtomicReference<>();
    new ForgeOpsTrackerPerformanceTrackingFilter()
        .doFilter(request("POST", "/checkout", INCOMING), mock(HttpServletResponse.class), (req, res) -> {
          try {
            String result = ForgeOpsTracker.httpSpan("post", URI.create("https://payments.internal:8443/charge?card=1"),
                headers -> {
                  sent.set(headers);
                  return "charged";
                });
            assertEquals("charged", result);
          } catch (Exception e) {
            throw new AssertionError(e);
          }
        });

    String header = sent.get().get("traceparent");
    assertNotNull(header);
    String[] parts = header.split("-");
    assertEquals("00", parts[0]);
    assertEquals(INCOMING_TRACE_ID, parts[1]);
    assertEquals("01", parts[3]);

    String trace = traces.poll(3, TimeUnit.SECONDS);
    assertNotNull(trace);
    String[] httpSpan = span(trace, "POST payments.internal");
    String[] root = span(trace, "POST /checkout");
    assertEquals(parts[2], httpSpan[0], "the header's parent id must be the recorded http span's own id");
    assertEquals(root[0], httpSpan[1]);
    assertTrue(trace.contains("\"kind\":\"http\""));
    assertFalse(trace.contains("card=1"));
  }

  @Test
  void httpSpanLeavesOutTheHeaderForHostsOutsideTheTargetsButStillRecordsTheSpan() throws Exception {
    init(config -> {
      config.setTraceCaptureThreshold(Duration.ZERO);
      config.setTracePropagationTargets(List.of("internal.example"));
    });
    AtomicReference<Map<String, String>> external = new AtomicReference<>();
    AtomicReference<Map<String, String>> internal = new AtomicReference<>();
    new ForgeOpsTrackerPerformanceTrackingFilter()
        .doFilter(request("GET", "/x", null), mock(HttpServletResponse.class), (req, res) -> {
          try {
            ForgeOpsTracker.httpSpan("GET", "https://api.thirdparty.test/v1", headers -> {
              external.set(headers);
              return null;
            });
            ForgeOpsTracker.httpSpan("GET", "https://orders.internal.example/v1", headers -> {
              internal.set(headers);
              return null;
            });
          } catch (Exception e) {
            throw new AssertionError(e);
          }
        });

    assertTrue(external.get().isEmpty());
    assertTrue(internal.get().containsKey("traceparent"));
    String trace = traces.poll(3, TimeUnit.SECONDS);
    assertNotNull(trace);
    span(trace, "GET api.thirdparty.test");
  }

  @Test
  void httpSpanWithPropagateTracesOffSendsNoHeader() throws Exception {
    init(config -> config.setPropagateTraces(false));
    AtomicReference<Map<String, String>> sent = new AtomicReference<>();
    new ForgeOpsTrackerFilter().doFilter(request("GET", "/x", null), mock(HttpServletResponse.class), (req, res) -> {
      try {
        ForgeOpsTracker.httpSpan("GET", "https://orders.internal/", headers -> {
          sent.set(headers);
          return null;
        });
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });

    assertTrue(sent.get().isEmpty());
  }

  @Test
  void httpSpanStillPropagatesTheRequestsTraceIdWithTrackTracingOff() throws Exception {
    init(config -> config.setTrackTracing(false));
    AtomicReference<Map<String, String>> sent = new AtomicReference<>();
    new ForgeOpsTrackerPerformanceTrackingFilter()
        .doFilter(request("GET", "/x", INCOMING), mock(HttpServletResponse.class), (req, res) -> {
          try {
            ForgeOpsTracker.httpSpan("GET", "https://orders.internal/", headers -> {
              sent.set(headers);
              return null;
            });
          } catch (Exception e) {
            throw new AssertionError(e);
          }
        });

    assertTrue(sent.get().get("traceparent").startsWith("00-" + INCOMING_TRACE_ID + "-"));
    assertNull(traces.poll(300, TimeUnit.MILLISECONDS));
  }

  @Test
  void httpSpanOutsideARequestSendsNoHeaderAndJustRunsTheCall() throws Exception {
    init();
    AtomicReference<Map<String, String>> sent = new AtomicReference<>();

    int result = ForgeOpsTracker.httpSpan("GET", URI.create("https://orders.internal/"), headers -> {
      sent.set(headers);
      return 5;
    });

    assertEquals(5, result);
    assertTrue(sent.get().isEmpty());
  }

  @Test
  void httpSpanRecordsTheSpanAndRethrowsWhenTheCallThrows() throws Exception {
    init(config -> config.setTraceCaptureThreshold(Duration.ZERO));
    IllegalStateException failure = new IllegalStateException("connection refused");
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    new ForgeOpsTrackerPerformanceTrackingFilter()
        .doFilter(request("GET", "/x", null), mock(HttpServletResponse.class), (req, res) -> {
          try {
            ForgeOpsTracker.httpSpan("GET", "https://down.internal/", headers -> {
              throw failure;
            });
          } catch (Exception e) {
            thrown.set(e);
          }
        });

    assertEquals(failure, thrown.get());
    String trace = traces.poll(3, TimeUnit.SECONDS);
    assertNotNull(trace);
    span(trace, "GET down.internal");
  }

  @Test
  void endpointUsesTheMatchedServletMappingButNeverTheDefaultServletOrTheRawPath() {
    HttpServletRequest request = request("GET", "/api/orders/42", null);
    HttpServletMapping mapping = mock(HttpServletMapping.class);
    when(request.getHttpServletMapping()).thenReturn(mapping);

    when(mapping.getMappingMatch()).thenReturn(MappingMatch.PATH);
    when(mapping.getPattern()).thenReturn("/api/*");
    assertEquals("GET /api/*", ForgeOpsTrackerRequestContext.endpoint(request));

    when(mapping.getMappingMatch()).thenReturn(MappingMatch.DEFAULT);
    when(mapping.getPattern()).thenReturn("/");
    assertNull(ForgeOpsTrackerRequestContext.endpoint(request));
    assertEquals("GET /api/orders/42", ForgeOpsTrackerRequestContext.transactionName(request));

    matchRoute(request, "/api/orders/{id}");
    assertEquals("GET /api/orders/{id}", ForgeOpsTrackerRequestContext.endpoint(request));
  }
}
