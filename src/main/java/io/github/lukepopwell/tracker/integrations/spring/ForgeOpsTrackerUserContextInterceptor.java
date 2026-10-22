package io.github.lukepopwell.tracker.integrations.spring;

import io.github.lukepopwell.tracker.ForgeOpsTracker;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Automatically identifies the affected user for every error reported during a request, when
 * Spring Security has authenticated one. Register as a {@code @Bean} implementing {@code
 * WebMvcConfigurer#addInterceptors}, alongside {@link ForgeOpsTrackerHandlerExceptionResolver}
 * (see that class's own doc comment for the registration snippet).
 *
 * <p>{@code SecurityContextHolder} is populated by Spring Security's own filter chain, which runs
 * as a Servlet {@code Filter} ahead of the {@code DispatcherServlet} (and therefore ahead of any
 * {@code HandlerInterceptor}) entirely: unlike Rails/Devise, there's no ordering concern here the
 * way there is for a middleware-ordered framework, since by the time {@link #preHandle} ever
 * runs, Spring Security (if configured at all) has already resolved the request's {@link
 * Authentication}.
 *
 * <p>An unauthenticated request is <strong>not</strong> simply {@code
 * getAuthentication() == null}: Spring Security's default {@code AnonymousAuthenticationFilter}
 * populates the context with a real, non-null {@link AnonymousAuthenticationToken} for every
 * request nobody actually logged into, and confirmed directly that {@code isAuthenticated()} on
 * one of those still returns {@code true} (a well-known Spring Security gotcha, not assumed):
 * that token's own class has to be excluded explicitly, not just checked for authentication.
 *
 * <p>{@code Authentication.getPrincipal()} returns a bare {@code Object}: unlike ASP.NET Core's
 * {@code ClaimsPrincipal}/{@code ClaimTypes} (the framework's own universal, well-known claim
 * types every auth handler populates), Spring Security's core module has no equivalent baked-in
 * convention at all, only {@code UserDetails#getUsername()} on implementations that choose to use
 * it, and even that's only ever this SDK's compile-time (`provided`) {@code
 * spring-security-core} dependency's own contract, not something every {@code Authentication}
 * implementation is guaranteed to expose. {@link Authentication#getName()} (inherited from {@code
 * java.security.Principal}) is the one thing every implementation genuinely guarantees, so that's
 * what's used for {@code username}; {@code id}/{@code email} are read reflectively off the
 * principal object itself (a {@code getId()}/{@code getEmail()} method, if either exists), the
 * closest Java equivalent to the duck-typing every other framework auto-detection in this repo
 * does for its own language's own dominant (or, here, simply best-available) auth convention.
 *
 * <p>Clears the thread-local in {@link #afterCompletion}, which Spring guarantees runs after the
 * handler completes regardless of outcome (including an exception the handler itself threw):
 * Tomcat/Jetty's own thread pool reuses worker threads across requests, so leaving this set would
 * leak one request's user into a later, unrelated request on the same thread, the same reason
 * every {@code ThreadLocal}-based auto-detection in this repo clears itself the same way.
 */
public class ForgeOpsTrackerUserContextInterceptor implements HandlerInterceptor {
  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && !(authentication instanceof AnonymousAuthenticationToken)) {
      ForgeOpsTracker.setUser(buildUser(authentication));
    }
    return true; // never blocks the request: this interceptor only observes
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    ForgeOpsTracker.setUser(Map.of());
  }

  private Map<String, Object> buildUser(Authentication authentication) {
    Map<String, Object> user = new LinkedHashMap<>();
    Object principal = authentication.getPrincipal();

    String id = reflectiveStringProperty(principal, "getId");
    if (id != null) {
      user.put("id", id);
    }
    String email = reflectiveStringProperty(principal, "getEmail");
    if (email != null) {
      user.put("email", email);
    }
    String username = authentication.getName();
    if (username != null && !username.isEmpty()) {
      user.put("username", username);
    }
    return user;
  }

  /**
   * Calls a no-arg getter on {@code target} reflectively and returns its result as a string, or
   * {@code null} if the method doesn't exist, isn't accessible, or itself returned null: a custom
   * principal missing a given getter just gets that one field skipped, never an exception thrown
   * back into request handling.
   */
  private String reflectiveStringProperty(Object target, String getterName) {
    try {
      Method method = target.getClass().getMethod(getterName);
      Object value = method.invoke(target);
      return value == null ? null : String.valueOf(value);
    } catch (ReflectiveOperationException | SecurityException e) {
      return null;
    }
  }
}
