package io.github.lukepopwell.tracker;

import java.util.function.Supplier;

/**
 * Everything this client knows about the current web request that isn't already owned by one of
 * the older, single-purpose thread-locals (the user, the breadcrumb trail, the span buffer): which
 * trace it belongs to, which remote span called it (if any), what it's called, and whether it
 * errored. Mirrors {@code gems/forge_ops_tracker}'s own {@code RequestState}. Created by the servlet
 * integration (see {@code ForgeOpsTrackerRequestContext}) and published on {@link ForgeOpsTracker}
 * for the duration of the request, so {@link ForgeOpsTracker#captureException}, {@link
 * ForgeOpsTracker#httpSpan}, and the request's own trace all agree on one trace id.
 *
 * <p>The trace id exists for every request, not only when span tracing is on: it's also what an
 * error event carries so ForgeOps can link it to errors reported by other services taking part in
 * the same trace.
 *
 * <p>{@link #transactionName()} and {@link #endpoint()} are read lazily, each time they're asked
 * for, rather than fixed when the request starts: under Spring MVC the matched route pattern only
 * exists once {@code DispatcherServlet} has picked a handler, so reading it on demand is what lets
 * an error reported from inside the handler itself already carry it, without a separate interceptor.
 *
 * <p>Internal; public only so the servlet and Spring integration packages can reach it.
 */
public final class RequestState {
  private final String traceId;
  private final String parentSpanId;
  private final Supplier<String> transactionName;
  private final Supplier<String> endpoint;
  private volatile boolean errored;

  public RequestState(String traceId, String parentSpanId, Supplier<String> transactionName,
      Supplier<String> endpoint) {
    this.traceId = traceId;
    this.parentSpanId = parentSpanId;
    this.transactionName = transactionName;
    this.endpoint = endpoint;
  }

  /**
   * Continues the caller's trace when {@code traceparent} is a usable W3C header value,
   * remembering the caller's span as this request's remote parent; starts a fresh trace otherwise.
   */
  public static RequestState fromTraceParent(String traceparent, Supplier<String> transactionName,
      Supplier<String> endpoint) {
    TraceParent.Context incoming = TraceParent.parse(traceparent);
    if (incoming != null) {
      return new RequestState(incoming.traceId(), incoming.parentSpanId(), transactionName, endpoint);
    }
    return new RequestState(TraceParent.generateTraceId(), null, transactionName, endpoint);
  }

  public String traceId() {
    return traceId;
  }

  /** The calling service's span this request runs under, or {@code null} when it started the trace itself. */
  public String parentSpanId() {
    return parentSpanId;
  }

  /** The same name performance samples and the request's root span use, e.g. {@code "GET /users/{id}"}. */
  public String transactionName() {
    return read(transactionName);
  }

  /**
   * {@code "<HTTP method> <route pattern>"}, e.g. {@code "GET /users/{id}"}; {@code null} until
   * (or unless) the framework has matched a route, never the raw path with ids in it.
   */
  public String endpoint() {
    return read(endpoint);
  }

  public void markErrored() {
    errored = true;
  }

  public boolean isErrored() {
    return errored;
  }

  // Never throws: a request wrapper misbehaving must not stop an error from being reported.
  private static String read(Supplier<String> supplier) {
    if (supplier == null) {
      return null;
    }
    try {
      return supplier.get();
    } catch (RuntimeException e) {
      return null;
    }
  }
}
