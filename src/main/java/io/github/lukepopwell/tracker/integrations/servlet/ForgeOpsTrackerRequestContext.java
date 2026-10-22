package io.github.lukepopwell.tracker.integrations.servlet;

import io.github.lukepopwell.tracker.ForgeOpsTracker;
import io.github.lukepopwell.tracker.RequestState;
import io.github.lukepopwell.tracker.TraceParent;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.MappingMatch;

/**
 * Gives every request one {@link RequestState} (its trace id, the caller's span when it arrived
 * with a {@code traceparent} header, its transaction name and endpoint) and publishes it on {@link
 * ForgeOpsTracker} for as long as the request runs. Internal: every {@code ForgeOpsTracker*Filter}
 * (and {@code ForgeOpsTrackerHandlerExceptionResolver}) calls {@link #enter}/{@link #exit} itself,
 * so there is nothing extra to register, and whichever of them happens to be outermost is the one
 * that publishes and later clears it; the rest find it already set and leave it alone. That keeps
 * the "order doesn't matter" promise every filter's own Javadoc makes: {@code
 * ForgeOpsTrackerFilter} always reports an unhandled exception while the state is still published,
 * because whichever filter owns it only clears it after the whole chain inside it has returned.
 *
 * <p>Also memoized as a request attribute, so a second dispatch of the same request (the
 * container's {@code ERROR} dispatch to an error page, an async redispatch) keeps the same trace id
 * instead of starting a new trace halfway through one request.
 *
 * <p>Never throws: a request wrapper misbehaving must never stop the request itself.
 */
public final class ForgeOpsTrackerRequestContext {
  static final String ATTRIBUTE = RequestState.class.getName();

  // HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE's value, spelled out rather than read off the
  // constant: Spring computes it at class load time (HandlerMapping.class.getName() + ...), so it
  // isn't a compile-time constant javac can inline, and reading it would need spring-webmvc at
  // runtime even in a plain servlet app that never has it.
  static final String SPRING_PATTERN_ATTRIBUTE = "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern";

  private ForgeOpsTrackerRequestContext() {}

  /**
   * Publishes this request's state on the current thread unless one already is. Returns whether
   * this call published it, to hand back to {@link #exit} in a {@code finally}. A no-op (false)
   * when the client isn't enabled.
   */
  public static boolean enter(ServletRequest request) {
    try {
      if (ForgeOpsTracker.currentRequestState() != null || !ForgeOpsTracker.configuration().isEnabled()) {
        return false;
      }
      ForgeOpsTracker.enterRequest(stateFor(request));
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** Clears the state {@link #enter} published; does nothing when it didn't. */
  public static void exit(boolean entered) {
    if (entered) {
      ForgeOpsTracker.exitRequest();
    }
  }

  /** Marks the current request as errored, so its trace is sent however fast it was. */
  static void markErrored() {
    RequestState state = ForgeOpsTracker.currentRequestState();
    if (state != null) {
      state.markErrored();
    }
  }

  static RequestState stateFor(ServletRequest request) {
    if (request.getAttribute(ATTRIBUTE) instanceof RequestState existing) {
      return existing;
    }
    String traceparent =
        request instanceof HttpServletRequest httpRequest ? httpRequest.getHeader(TraceParent.HEADER) : null;
    RequestState state =
        RequestState.fromTraceParent(traceparent, () -> transactionName(request), () -> endpoint(request));
    request.setAttribute(ATTRIBUTE, state);
    return state;
  }

  /**
   * {@code "<HTTP method> <route pattern>"} under Spring MVC once a handler has matched (e.g.
   * {@code "GET /users/{id}"}), the raw request URI otherwise: the name performance samples and
   * the request's root span have always used.
   */
  static String transactionName(ServletRequest request) {
    if (!(request instanceof HttpServletRequest httpRequest)) {
      return "unknown";
    }
    Object pattern = httpRequest.getAttribute(SPRING_PATTERN_ATTRIBUTE);
    String route = pattern != null ? pattern.toString() : httpRequest.getRequestURI();
    return httpRequest.getMethod() + " " + route;
  }

  /**
   * {@code "<HTTP method> <route pattern>"} from the framework's own matched route, never the raw
   * path: Spring MVC's matched pattern when there is one, otherwise the servlet mapping that
   * matched (e.g. {@code "GET /orders/*"}), unless that was only the container's default servlet
   * ({@code "/"}), which says nothing about the route. {@code null} when neither is known yet.
   */
  static String endpoint(ServletRequest request) {
    if (!(request instanceof HttpServletRequest httpRequest) || httpRequest.getMethod() == null) {
      return null;
    }
    Object pattern = httpRequest.getAttribute(SPRING_PATTERN_ATTRIBUTE);
    if (pattern != null) {
      return httpRequest.getMethod() + " " + pattern;
    }
    HttpServletMapping mapping = httpRequest.getHttpServletMapping();
    if (mapping == null || mapping.getMappingMatch() == null || mapping.getMappingMatch() == MappingMatch.DEFAULT) {
      return null;
    }
    String servletPattern = mapping.getPattern();
    if (servletPattern == null) {
      return null;
    }
    return httpRequest.getMethod() + " " + (servletPattern.isEmpty() ? "/" : servletPattern);
  }
}
