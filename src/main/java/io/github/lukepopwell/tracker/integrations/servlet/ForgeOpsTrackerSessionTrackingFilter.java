package io.github.lukepopwell.tracker.integrations.servlet;

import io.github.lukepopwell.tracker.ForgeOpsTracker;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Counts every request as a session (crash-free unless an unhandled exception actually escaped
 * it, or the response's own final status ended up 5xx) toward the crash-free rate on a project's
 * Releases page. A separate, independent filter from {@link ForgeOpsTrackerFilter}, not folded
 * into it, since session tracking runs regardless of whether error reporting is even configured:
 * mirrors {@code gems/forge_ops_tracker}'s own split between its Rack middleware (sessions) and
 * its {@code Rails.error} hook (errors), two genuinely independent mechanisms.
 *
 * <p>The status-code check matters in its own right, not just as a defensive fallback: Spring
 * Boot's own {@code DispatcherServlet} routinely resolves a controller's thrown exception into
 * an error response (via {@code @ExceptionHandler}/{@code @ControllerAdvice}) before it ever
 * propagates back out through the raw servlet filter chain as a thrown exception at all; a
 * plain try/catch around {@code chain.doFilter} alone would miss that case entirely.
 *
 * <p>Order relative to {@link ForgeOpsTrackerFilter} doesn't matter (unlike Express, where the
 * session-tracking middleware has to run first): a servlet {@code Filter} genuinely wraps the
 * rest of the chain the way Rack's {@code app.call(env)} does, so this filter observes the real
 * exception and the real final response either way.
 */
public class ForgeOpsTrackerSessionTrackingFilter implements Filter {
  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    boolean entered = ForgeOpsTrackerRequestContext.enter(request);
    boolean crashed = false;
    try {
      chain.doFilter(request, response);
      if (response instanceof HttpServletResponse httpResponse) {
        crashed = httpResponse.getStatus() >= 500;
      }
    } catch (Throwable error) {
      crashed = true;
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
      // else is a checked exception we can't legally rethrow as-is; wrap it, same as
      // ForgeOpsTrackerFilter's own doFilter does.
      throw new ServletException(error);
    } finally {
      recordSession(crashed);
      ForgeOpsTrackerRequestContext.exit(entered);
    }
  }

  /**
   * Recording itself must never be able to replace whatever this filter is about to (re)throw
   * or return, the same reasoning as {@link ForgeOpsTrackerFilter#report}.
   */
  private void recordSession(boolean crashed) {
    try {
      ForgeOpsTracker.recordSession(crashed);
    } catch (RuntimeException recordingFailure) {
      // Swallowed deliberately; see the Javadoc above.
    }
  }
}
