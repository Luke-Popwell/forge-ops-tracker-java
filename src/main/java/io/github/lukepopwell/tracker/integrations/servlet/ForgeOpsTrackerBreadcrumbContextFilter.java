package io.github.lukepopwell.tracker.integrations.servlet;

import io.github.lukepopwell.tracker.ForgeOpsTracker;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;

/**
 * Gives every request a fresh, empty breadcrumb trail to accumulate into, via {@code
 * ForgeOpsTracker.addBreadcrumb} or the automatic controller breadcrumb {@link
 * ForgeOpsTrackerPerformanceTrackingFilter} records, so one request's trail never bleeds into
 * another's on a reused Tomcat/Jetty worker thread. A separate, independent filter again, the
 * same "own file, own class" shape {@link ForgeOpsTrackerSessionTrackingFilter}'s own doc comment
 * already establishes for this client, matching {@code
 * gems/forge_ops_tracker::Middleware::BreadcrumbContext}'s own identical "sibling to
 * SessionTracking/UserContext, not folded into either" call for the same reason: a distinct
 * concern from either of those.
 *
 * <p>Order relative to the other {@code ForgeOpsTracker*Filter}s doesn't matter, the same
 * reasoning {@link ForgeOpsTrackerSessionTrackingFilter}'s own doc comment gives: a servlet
 * {@code Filter} genuinely wraps the rest of the chain, so as long as this filter is installed at
 * all, it wraps every other filter, the servlet itself, and (running under Spring MVC) the whole
 * of {@code DispatcherServlet}'s own dispatch, including any {@code HandlerInterceptor} and the
 * handler method itself: all of that happens strictly inside {@code chain.doFilter} from this
 * filter's point of view, on the very same thread, so no separate Spring-specific breadcrumb-
 * scoping interceptor is needed the way {@code ForgeOpsTrackerUserContextInterceptor} exists for
 * automatic user detection (that one needs Spring Security's own request-scoped {@code
 * SecurityContextHolder}, a genuinely different, Spring-specific data source; a breadcrumb trail
 * needs nothing Spring-specific at all, just the thread/request boundary this filter already
 * owns). Confirmed directly, not assumed: see {@code ForgeOpsTrackerBreadcrumbContextFilterTest}'s
 * own test simulating a {@code HandlerExceptionResolver} call (standing in for Spring's own
 * exception-resolution, which always runs from inside {@code DispatcherServlet.doDispatch}, i.e.
 * inside this filter's {@code chain.doFilter}) reading back a breadcrumb added earlier in that
 * same simulated dispatch.
 *
 * <p>The clear in {@code finally} is unconditional, even when {@code trackBreadcrumbs} is off:
 * only the fresh-buffer start ({@link ForgeOpsTracker#beginBreadcrumbTrail}) is flag-gated, the
 * clear ({@link ForgeOpsTracker#endBreadcrumbTrail}) never is. This is not a stylistic choice:
 * sdks/php's own {@code ForgeOpsTrackerBreadcrumbMiddleware} first shipped with the clear itself
 * flag-gated too, which leaked a manually-added trail across requests on a process kept alive
 * across many requests (Octane) whenever the flag happened to be off; see {@link
 * ForgeOpsTracker#endBreadcrumbTrail}'s own Javadoc for the same fix applied here from the start.
 */
public class ForgeOpsTrackerBreadcrumbContextFilter implements Filter {
  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    boolean entered = ForgeOpsTrackerRequestContext.enter(request);
    ForgeOpsTracker.beginBreadcrumbTrail();
    try {
      chain.doFilter(request, response);
    } finally {
      ForgeOpsTracker.endBreadcrumbTrail();
      ForgeOpsTrackerRequestContext.exit(entered);
    }
  }
}
