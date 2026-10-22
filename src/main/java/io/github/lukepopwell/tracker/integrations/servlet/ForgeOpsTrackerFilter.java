package io.github.lukepopwell.tracker.integrations.servlet;

import io.github.lukepopwell.tracker.ForgeOpsTracker;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reports any exception that propagates out of the filter chain, then rethrows it unchanged:
 * the container's own error handling (an {@code <error-page>} mapping, Spring Boot's
 * {@code /error} whitelabel page, whatever else is configured) runs exactly as if this filter
 * weren't installed. Register it last (closest to the servlet) so it sees the same exception the
 * container itself will end up handling, not something already partially handled upstream.
 *
 * <p>A reported error carries the request's trace id, transaction name, and endpoint (see {@link
 * ForgeOpsTrackerRequestContext}), and marks the request as errored so its trace is sent however
 * fast it was.
 *
 * <p>{@code jakarta.servlet-api} is a compile-time-only ({@code provided}) dependency of this
 * library: a consuming app brings its own Servlet container/version, this class is only usable
 * if that's already on the classpath.
 */
public class ForgeOpsTrackerFilter implements Filter {
  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    boolean entered = ForgeOpsTrackerRequestContext.enter(request);
    try {
      chain.doFilter(request, response);
    } catch (Throwable error) {
      report(error, request);
      if (error instanceof IOException ioException) {
        throw ioException;
      }
      if (error instanceof ServletException servletException) {
        throw servletException;
      }
      if (error instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      if (error instanceof Error jvmError) {
        throw jvmError;
      }
      // Filter#doFilter's throws clause only permits IOException/ServletException, so anything
      // else is a checked exception we can't legally rethrow as-is: wrap it, same as the
      // container itself would have to.
      throw new ServletException(error);
    } finally {
      ForgeOpsTrackerRequestContext.exit(entered);
    }
  }

  /**
   * Reporting itself must never be able to replace the exception this filter is about to
   * rethrow. {@link ForgeOpsTracker#captureException} already never throws, but a defensive
   * request wrapper's {@code getMethod()}/{@code getRequestURI()} returning something
   * unexpected (or throwing) is outside this client's control: caught here so that failure
   * can never masquerade as the real exception propagating out of {@link #doFilter}.
   */
  private void report(Throwable error, ServletRequest request) {
    try {
      ForgeOpsTracker.captureException(unwrap(error), requestContext(request));
    } catch (RuntimeException reportingFailure) {
      // Swallowed deliberately: see the Javadoc above.
    }
  }

  private Throwable unwrap(Throwable error) {
    if (error instanceof ServletException servletException && servletException.getRootCause() != null) {
      return servletException.getRootCause();
    }
    return error;
  }

  private Map<String, Object> requestContext(ServletRequest request) {
    Map<String, Object> context = new LinkedHashMap<>();
    if (request instanceof HttpServletRequest httpRequest) {
      // A bare Map.of() would throw NullPointerException if either value is null: verified
      // directly (a Mockito mock with an unstubbed method returns null, the same as some
      // non-standard request wrapper legitimately could): so absent values are omitted rather
      // than risking that NPE replacing the real exception being reported.
      putIfPresent(context, "method", httpRequest.getMethod());
      putIfPresent(context, "path", httpRequest.getRequestURI());
    }
    return context;
  }

  private void putIfPresent(Map<String, Object> context, String key, Object value) {
    if (value != null) {
      context.put(key, value);
    }
  }
}
