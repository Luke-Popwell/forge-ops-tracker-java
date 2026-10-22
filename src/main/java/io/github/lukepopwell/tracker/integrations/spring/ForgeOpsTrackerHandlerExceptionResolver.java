package io.github.lukepopwell.tracker.integrations.spring;

import io.github.lukepopwell.tracker.ForgeOpsTracker;
import io.github.lukepopwell.tracker.integrations.servlet.ForgeOpsTrackerRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Reports every exception a Spring MVC handler raises, then always returns {@code null} so
 * Spring's own resolution chain (a {@code @ExceptionHandler}, {@code DefaultErrorAttributes}, the
 * {@code /error} whitelabel page, or anything else registered) still runs exactly as if this
 * resolver weren't registered: the same "observe, don't interfere" contract as the Express/
 * Fastify integrations in the Node client.
 *
 * <p>Ordered at {@link Ordered#HIGHEST_PRECEDENCE} deliberately: Spring calls resolvers in order
 * and stops at the first one that returns a non-null result, so this one has to run <em>before</em>
 * any resolver that might actually handle the exception, not after: otherwise it would never see
 * exceptions other resolvers already claimed.
 *
 * <p>A reported error carries the request's trace id, transaction name, and endpoint (Spring's
 * matched route pattern, e.g. {@code "GET /users/{id}"}), even in an app that registers none of
 * the servlet filters: the request's trace context is then created here, continuing the caller's
 * trace when the request carried a {@code traceparent} header.
 *
 * <p>Register it as a {@code @Bean} in a Spring MVC / Spring Boot application; {@code
 * spring-webmvc} is a compile-time-only ({@code provided}) dependency of this library.
 */
public class ForgeOpsTrackerHandlerExceptionResolver implements HandlerExceptionResolver, Ordered {
  @Override
  public ModelAndView resolveException(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception error) {
    // Reporting must never throw out of resolveException: Spring calls every resolver in the
    // chain in turn, and one throwing would break resolution for this request entirely, taking
    // down exactly the error handling this class exists to observe, not interfere with.
    boolean entered = ForgeOpsTrackerRequestContext.enter(request);
    try {
      ForgeOpsTracker.captureException(error, requestContext(request));
    } catch (RuntimeException reportingFailure) {
      // Swallowed deliberately: see above.
    } finally {
      ForgeOpsTrackerRequestContext.exit(entered);
    }
    return null;
  }

  private Map<String, Object> requestContext(HttpServletRequest request) {
    Map<String, Object> context = new LinkedHashMap<>();
    // A bare Map.of() would throw NullPointerException if either value is null: verified
    // directly against an unstubbed mock request, the same as some non-standard request wrapper
    // legitimately could return: so absent values are omitted rather than risking that NPE.
    putIfPresent(context, "method", request.getMethod());
    putIfPresent(context, "path", request.getRequestURI());
    return context;
  }

  private void putIfPresent(Map<String, Object> context, String key, Object value) {
    if (value != null) {
      context.put(key, value);
    }
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }
}
