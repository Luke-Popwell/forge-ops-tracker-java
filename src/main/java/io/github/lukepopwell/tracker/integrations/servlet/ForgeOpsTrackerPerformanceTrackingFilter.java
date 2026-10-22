package io.github.lukepopwell.tracker.integrations.servlet;

import io.github.lukepopwell.tracker.ForgeOpsTracker;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Times every request (so a dashboard widget on ForgeOps can show which parts of your app are
 * actually slow, not just which ones raise). A separate, independent filter again, same
 * reasoning {@link ForgeOpsTrackerSessionTrackingFilter}'s own Javadoc documents. Order relative
 * to the other filters doesn't matter for the same reason it doesn't there either: a servlet
 * {@code Filter} genuinely wraps the rest of the chain, so a plain wall-clock diff around {@code
 * chain.doFilter} covers the request whether it succeeds, is caught and converted to a response,
 * or actually throws back out through here.
 *
 * <p>transactionName is {@code "<HTTP method> <route pattern>"}, e.g. {@code "GET /users/{id}"},
 * not the raw URI, when running under Spring MVC: {@code HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE}
 * is Spring's own matched handler mapping pattern, set once dispatch actually happens, which
 * keeps a distinct user id from exploding into its own separate transaction the way the literal
 * URI would. The attribute's name is spelled out in {@link ForgeOpsTrackerRequestContext} rather
 * than read off Spring's constant (which isn't a compile-time constant, so reading it needed
 * {@code spring-webmvc} at runtime), so a plain servlet app without Spring falls back to the raw
 * request URI, as does a request no handler ever matched (a 404).
 *
 * <p>Each request's trace continues the caller's when the request arrived with a W3C {@code
 * traceparent} header (the root span is parented under the caller's span), and is sent when it
 * was slow or when the request errored: an exception escaping the chain, or anything reported
 * with {@code ForgeOpsTracker.captureException} while it ran.
 *
 * <p>Also records one automatic "controller" breadcrumb per request, gated on {@code
 * trackBreadcrumbs} independently of {@code trackPerformance} (an app could want the trail
 * without the timing data, or vice versa): the one and only automatic breadcrumb source this
 * client has, since (confirmed by grep before adding this) there is no query-level/ORM
 * instrumentation anywhere in this client for a second one to hang off of, unlike the Ruby gem's
 * own {@code sql.active_record} subscription. Recorded from the same {@code finally} as the
 * duration above, so (the same documented gap sdks/python's Django/Flask integrations and
 * sdks/php's Symfony integration both already have, for the identical structural reason) this
 * breadcrumb can never show up on that exact same request's own error when {@code
 * ForgeOpsTrackerFilter} is registered closest to the servlet as its own Javadoc recommends: that
 * filter, being innermost, reports the exception before this outer filter's own {@code finally}
 * ever runs to record it. A later, unrelated request's manually-added breadcrumbs are never
 * affected either way.
 */
public class ForgeOpsTrackerPerformanceTrackingFilter implements Filter {
  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    boolean entered = ForgeOpsTrackerRequestContext.enter(request);
    long start = System.nanoTime();
    Instant startedAt = Instant.now();
    beginTrace();
    try {
      chain.doFilter(request, response);
    } catch (Throwable error) {
      // Only observed on its way out, never handled: precise rethrow keeps it exactly as thrown.
      ForgeOpsTrackerRequestContext.markErrored();
      throw error;
    } finally {
      double durationMs = (System.nanoTime() - start) / 1_000_000.0;
      String transactionName = transactionName(request);
      recordPerformance(transactionName, durationMs);
      recordBreadcrumb(request, response, transactionName);
      finishTrace(transactionName, startedAt, durationMs);
      ForgeOpsTrackerRequestContext.exit(entered);
    }
  }

  private void beginTrace() {
    try {
      ForgeOpsTracker.beginTrace();
    } catch (RuntimeException recordingFailure) {
      // Swallowed deliberately: tracing must never affect the request.
    }
  }

  private void finishTrace(String transactionName, Instant startedAt, double durationMs) {
    try {
      ForgeOpsTracker.finishTrace(transactionName, startedAt, durationMs);
    } catch (RuntimeException recordingFailure) {
      // Swallowed deliberately: tracing must never affect the request.
    }
  }

  private String transactionName(ServletRequest request) {
    try {
      return ForgeOpsTrackerRequestContext.transactionName(request);
    } catch (RuntimeException e) {
      return "unknown";
    }
  }

  /**
   * Recording itself must never be able to replace whatever this filter is about to (re)throw
   * or return, same reasoning as {@link ForgeOpsTrackerSessionTrackingFilter#recordSession}.
   */
  private void recordPerformance(String transactionName, double durationMs) {
    try {
      ForgeOpsTracker.recordPerformance(transactionName, durationMs);
    } catch (RuntimeException recordingFailure) {
      // Swallowed deliberately; see the Javadoc above.
    }
  }

  /**
   * Recording itself must never be able to replace whatever this filter is about to (re)throw or
   * return, the identical reasoning as {@link #recordPerformance} just above. {@code
   * transactionName} (already "{@code <method> <route>}") doubles as the breadcrumb's message,
   * the same value used for the performance sample; {@code path} in {@code data} is always the
   * raw request URI regardless, so the two stay distinguishable even when a matched Spring route
   * pattern makes {@code transactionName} differ from it.
   */
  private void recordBreadcrumb(ServletRequest request, ServletResponse response, String transactionName) {
    try {
      Integer status = response instanceof HttpServletResponse httpResponse ? httpResponse.getStatus() : null;
      Map<String, Object> data = new LinkedHashMap<>();
      if (status != null) {
        data.put("status", status);
      }
      if (request instanceof HttpServletRequest httpRequest) {
        data.put("path", httpRequest.getRequestURI());
      }
      String level = status != null && status >= 500 ? "error" : "info";
      ForgeOpsTracker.recordBreadcrumb("controller", transactionName, level, data);
    } catch (RuntimeException recordingFailure) {
      // Swallowed deliberately; see the Javadoc above.
    }
  }
}
