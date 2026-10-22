package io.github.lukepopwell.tracker;

import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Main entry point. Mirrors {@code ForgeOpsTracker.configure}/{@code Rails.error} in the Ruby gem
 * and {@code init()}/{@code captureException()} in the Python/PHP/Node clients.
 *
 * <h2>Uncaught exceptions, without a monitor/handler split</h2>
 *
 * <p>Node needs a careful choice between {@code uncaughtException} (which suppresses the
 * process's own default crash behavior the moment anything listens for it) and {@code
 * uncaughtExceptionMonitor} (purpose-built to observe without side effects). Java doesn't have
 * that hazard: verified directly against a real uncaught throw, installing a {@link
 * Thread.UncaughtExceptionHandler} via {@link Thread#setDefaultUncaughtExceptionHandler} does not
 * change whether or how the JVM exits: a thread that dies from an uncaught exception dies
 * either way, and the process still exits {@code 1} whether or not a handler is installed.
 *
 * <p>The one real side effect is narrower: installing a handler replaces the JVM's own default
 * behavior of printing {@code "Exception in thread ... "} plus the stack trace to {@code stderr}
 * (also verified directly). This client's handler restores that: it chains to whatever default
 * handler was already installed, if any, and otherwise reproduces the JVM's own default
 * printed-to-stderr output itself: so installing this client changes nothing observable about
 * an uncaught exception besides also reporting it.
 */
public final class ForgeOpsTracker {
  private static volatile Configuration configuration;
  private static volatile Reporter reporter;
  private static volatile SessionFlusher sessionFlusher;
  private static volatile PerformanceFlusher performanceFlusher;
  private static volatile Thread.UncaughtExceptionHandler previousHandler;
  private static boolean installedHandler;

  /**
   * The affected user set via {@link #setUser}, if any: a {@link ThreadLocal}, the direct Java
   * analog to {@code Thread.current} in the Ruby client's own equivalent, and the correct
   * isolation boundary for a typical servlet container's one-thread-per-request model.
   */
  private static final ThreadLocal<Map<String, Object>> currentUser = new ThreadLocal<>();

  /**
   * The current thread's breadcrumb trail, if one has been started: same {@link ThreadLocal}
   * isolation boundary as {@link #currentUser} above, for the same reason (a servlet container's
   * one-thread-per-request model). Unlike {@code currentUser}, nothing ever reads this field
   * directly from outside this class; every access goes through {@link #breadcrumbBuffer()} (lazy
   * creation) or {@link #currentBreadcrumbs()} (read-only, never creates one), the same "own file,
   * own class" encapsulation {@link BreadcrumbBuffer} itself gets.
   */
  private static final ThreadLocal<BreadcrumbBuffer> breadcrumbs = new ThreadLocal<>();

  private static final ThreadLocal<SpanBuffer> spanBuffer = new ThreadLocal<>();

  /**
   * The current web request's {@link RequestState} (trace id, remote parent, transaction name,
   * endpoint, errored flag), published by the servlet integration for the duration of the request:
   * same {@link ThreadLocal} isolation boundary as {@link #currentUser}, for the same reason.
   */
  private static final ThreadLocal<RequestState> requestState = new ThreadLocal<>();
  private static volatile SpanQueue spanQueue;
  private static volatile MetricBuffer metricBuffer;
  private static volatile MetricBuffer infrastructureMetricBuffer;

  /** Set once the startup change snapshot has been handed off, so a second {@link #init} never sends another. */
  private static final AtomicBoolean changeSnapshotSent = new AtomicBoolean();

  /** Set once the "not sending from this environment" warning has printed, so it prints once per process. */
  private static final AtomicBoolean notSendingWarned = new AtomicBoolean();

  private ForgeOpsTracker() {}

  /**
   * Prints {@link Configuration#notSendingWarning} at most once (tracked by {@code warned}):
   * through the configured logger when there is one, otherwise to {@code err}, since a DSN that
   * quietly sends nothing is the one setup mistake a new install can't otherwise see. Silent
   * without a DSN, and never throws into the caller.
   */
  static void warnIfNotSending(Configuration config, AtomicBoolean warned, PrintStream err) {
    try {
      String warning = config.notSendingWarning();
      if (warning == null || !warned.compareAndSet(false, true)) {
        return;
      }
      if (config.getLogger() != null) {
        config.log(warning);
      } else {
        err.println("[ForgeOps] " + warning);
      }
    } catch (RuntimeException e) {
      // A logger that throws is never a reason to fail init.
    }
  }

  public static synchronized Configuration configuration() {
    if (configuration == null) {
      configuration = new Configuration();
    }
    return configuration;
  }

  /** Installs with defaults / whatever {@code FORGE_OPS_*} environment variables are set. */
  public static void init() {
    init(config -> {});
  }

  public static void init(Consumer<Configuration> configurer) {
    init(configurer, true);
  }

  /**
   * @param installUncaughtExceptionHandler whether to also install the default
   *     uncaught-exception handler described above. On by default; disable it if the host app
   *     already has its own handler it would rather manage chaining for itself.
   */
  public static synchronized void init(Consumer<Configuration> configurer, boolean installUncaughtExceptionHandler) {
    configurer.accept(configuration());
    warnIfNotSending(configuration(), notSendingWarned, System.err);
    reporter = new Reporter(configuration());
    if (installUncaughtExceptionHandler) {
      installUncaughtExceptionHandlerChain();
    }
    sendChangeSnapshotInBackground(reporter);
  }

  /**
   * Sends the startup change snapshot (see {@link Configuration#isDetectChanges}) once per process,
   * from a short-lived daemon thread so reading the environment never delays {@code init}; the
   * delivery itself then goes through the same background queue as errors. A no-op when the
   * client isn't enabled, and nothing here can throw into the caller.
   */
  private static void sendChangeSnapshotInBackground(Reporter target) {
    Configuration config = configuration();
    if (!config.isDetectChanges() || !config.isEnabled() || !changeSnapshotSent.compareAndSet(false, true)) {
      return;
    }
    try {
      Thread thread = new Thread(() -> target.sendChangeSnapshot(config.isTrackEnvVarNames() ? safeEnv() : Map.of()),
          "forge-ops-tracker-change-snapshot");
      thread.setDaemon(true);
      thread.start();
    } catch (RuntimeException | OutOfMemoryError e) {
      // Thread creation can fail under a SecurityManager or when the JVM is out of native threads;
      // neither is a reason to fail the host app's startup.
      config.log("change snapshot not started: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static Map<String, String> safeEnv() {
    try {
      return System.getenv();
    } catch (RuntimeException e) {
      return Map.of();
    }
  }

  public static void captureException(Throwable error) {
    captureException(error, Map.of());
  }

  /**
   * The affected user defaults to whatever {@link #setUser} last set for the current thread. The
   * breadcrumb trail attached is always whatever this same thread's own current trail holds (see
   * {@link #currentBreadcrumbs()}): unlike {@code user}, there is no overload to pass one in
   * explicitly, the same "always read off the thread, never an argument" shape the Ruby gem's own
   * {@code ErrorSubscriber#report} gives breadcrumbs.
   *
   * <p>During a web request the event also says where it happened: {@code trace_id}, {@code
   * transaction_name}, and {@code endpoint} (see {@link RequestState}), and the request's trace is
   * then sent however fast the request was. Outside a request these are left out, except {@code
   * trace_id} inside a trace started some other way.
   */
  public static void captureException(Throwable error, Map<String, Object> context) {
    captureException(error, context, currentUser.get());
  }

  /** Same as above, but with an explicit {@code user} overriding whatever {@link #setUser} set. */
  public static void captureException(Throwable error, Map<String, Object> context, Map<String, Object> user) {
    RequestState state = requestState.get();
    if (state == null) {
      reporter().report(error, context, user, currentBreadcrumbs(), currentTraceId(), null, null);
      return;
    }
    if (error != null) {
      state.markErrored();
    }
    reporter().report(error, context, user, currentBreadcrumbs(), state.traceId(), state.transactionName(),
        state.endpoint());
  }

  /**
   * Sends every reported error that is still waiting on the background delivery thread, blocking
   * for at most {@link DeliveryQueue#DRAIN_TIMEOUT} (5 seconds). A JVM shutdown hook already does
   * this when the program exits normally (returning from {@code main}, {@code System.exit}, or an
   * uncaught exception), so a CLI tool or cron job needs nothing more; call it yourself before
   * {@code Runtime.halt}, or whenever you need the errors sent before going on. Returns false if
   * the timeout ran out first. Recorded changes and the startup change snapshot share this queue
   * and are sent too.
   */
  public static boolean flush() {
    return flush(DeliveryQueue.DRAIN_TIMEOUT);
  }

  /** Same as {@link #flush()}, waiting at most {@code timeout}. */
  public static boolean flush(Duration timeout) {
    return reporter().flush(timeout);
  }

  /**
   * Manually attaches an affected user to whatever gets reported for the rest of this thread (a
   * servlet request, a background worker): for a plain Servlet app, or a non-Spring-Security auth
   * setup, call this from your own filter/interceptor, wherever the current user is available;
   * see {@code ForgeOpsTrackerUserContextInterceptor} in the {@code integrations.spring} package
   * for Spring Security's own automatic detection. Passing an empty map (or null) clears whatever
   * was set, e.g. at the end of a request.
   */
  public static void setUser(Map<String, Object> user) {
    if (user == null || user.isEmpty()) {
      currentUser.remove();
    } else {
      currentUser.set(user);
    }
  }

  /**
   * Adds one breadcrumb to the current thread's trail (a servlet request, or a background
   * worker/CLI tool/console session outside a request entirely: see {@link #breadcrumbBuffer()},
   * which lazily starts one if {@link #beginBreadcrumbTrail} never ran on this thread, the same
   * "works standalone, no specific setup required" shape {@link #setUser} already has). Works
   * whether or not {@code trackBreadcrumbs} is on: that flag only gates the automatic controller
   * source the servlet performance-tracking filter records, never this manual call, the same
   * contract the Ruby gem's own {@code ForgeOpsTracker.add_breadcrumb} doc comment gives.
   *
   * <p>Four overloads, cascading down to sensible defaults ({@code category} "custom", {@code
   * level} "info", {@code data} empty) exactly the way {@code category:}/{@code level:}/{@code
   * data:} default in the Ruby gem's own keyword-argument signature: Java has no default
   * arguments, so this mirrors {@link #captureException}'s own cascading-overload convention
   * instead of {@link #setUser}'s single-signature one, since {@code setUser} takes one already-
   * structured {@code Map} with nothing to cascade, while a breadcrumb's four independently
   * optional pieces are exactly what {@code captureException(error)} /
   * {@code captureException(error, context)} / {@code captureException(error, context, user)}
   * already cascade through.
   */
  public static void addBreadcrumb(String message) {
    addBreadcrumb(message, "custom", "info", Map.of());
  }

  public static void addBreadcrumb(String message, String category) {
    addBreadcrumb(message, category, "info", Map.of());
  }

  public static void addBreadcrumb(String message, String category, String level) {
    addBreadcrumb(message, category, level, Map.of());
  }

  public static void addBreadcrumb(String message, String category, String level, Map<String, Object> data) {
    breadcrumbBuffer().add(category, message, level, data);
  }

  /**
   * Records one explicit change (a feature flag flipped, a config value edited, a migration run) so
   * ForgeOps can line it up against errors and performance. Delivered on the same background thread
   * as errors, so this never blocks; a no-op when the client isn't enabled, and never throws. See
   * {@link Change} for the optional fields ({@code details}, {@code actor}, {@code url} and more).
   */
  public static void recordChange(Change change) {
    reporter().recordChange(change);
  }

  /**
   * {@code kind} is one of {@code feature_flag}, {@code config}, {@code migration}, {@code
   * dependency}, {@code infrastructure} or {@code other}; anything else is sent as {@code other}.
   */
  public static void recordChange(String kind, String title) {
    recordChange(Change.of(kind, title));
  }

  public static void recordChange(String kind, String title, Map<String, Object> details) {
    recordChange(Change.of(kind, title).details(details));
  }

  private static synchronized Reporter reporter() {
    if (reporter == null) {
      reporter = new Reporter(configuration());
    }
    return reporter;
  }

  /**
   * Internal; called by the servlet session-tracking filter, never by host app code directly
   * (there's nothing for a caller to decide here beyond what the filter itself already
   * observed).
   */
  public static void recordSession(boolean crashed) {
    Configuration config = configuration();
    if (!config.isTrackSessions() || !config.isEnabled()) {
      return;
    }
    sessionFlusher().recordSession(crashed);
  }

  private static synchronized SessionFlusher sessionFlusher() {
    if (sessionFlusher == null) {
      sessionFlusher = new SessionFlusher(configuration(), new Client(configuration()));
    }
    return sessionFlusher;
  }

  /**
   * Internal; called by the servlet performance-tracking filter, never by host app code
   * directly, same reasoning as {@link #recordSession} above.
   */
  public static void recordPerformance(String transactionName, double durationMs) {
    Configuration config = configuration();
    if (!config.isTrackPerformance() || !config.isEnabled()) {
      return;
    }
    performanceFlusher().record(transactionName, durationMs);
  }

  private static synchronized PerformanceFlusher performanceFlusher() {
    if (performanceFlusher == null) {
      performanceFlusher = new PerformanceFlusher(configuration(), new Client(configuration()));
    }
    return performanceFlusher;
  }

  /**
   * Internal; called by the servlet performance-tracking filter's own automatic controller
   * breadcrumb, never by host app code directly (same reasoning as {@link #recordPerformance}
   * above, and same shape: this is the one call site every automatic source funnels through, so
   * the {@code trackBreadcrumbs}/{@code isEnabled} gate lives here once, not repeated at each
   * integration). Unlike {@link #addBreadcrumb}, this is gated: an app that wants only its own
   * hand-added breadcrumbs, with none of the automatic ones, turns {@code trackBreadcrumbs} off
   * and still gets {@link #addBreadcrumb}, the same distinction the Ruby gem's own {@code
   * Railtie} draws between its automatic subscriptions and the always-on manual API.
   */
  public static void recordBreadcrumb(String category, String message, String level, Map<String, Object> data) {
    Configuration config = configuration();
    if (!config.isTrackBreadcrumbs() || !config.isEnabled()) {
      return;
    }
    breadcrumbBuffer().add(category, message, level, data);
  }

  /**
   * Internal; called by {@code ForgeOpsTrackerBreadcrumbContextFilter} at the start of every
   * request to give it a fresh, empty trail, the same per-request reset {@code
   * gems/forge_ops_tracker::Middleware::BreadcrumbContext} performs for Ruby. A no-op (no buffer
   * started at all) when {@code trackBreadcrumbs} is off or the client isn't enabled: {@link
   * #endBreadcrumbTrail} still runs unconditionally in that case (see its own Javadoc), so this
   * asymmetry is deliberate, not a gap.
   */
  public static void beginBreadcrumbTrail() {
    Configuration config = configuration();
    if (!config.isTrackBreadcrumbs() || !config.isEnabled()) {
      return;
    }
    breadcrumbs.set(new BreadcrumbBuffer(config));
  }

  /**
   * Internal; called by {@code ForgeOpsTrackerBreadcrumbContextFilter} in a {@code finally} at
   * the end of every request, unconditionally, regardless of whether {@link #beginBreadcrumbTrail}
   * actually started a buffer: sdks/php's own {@code ForgeOpsTrackerBreadcrumbMiddleware} first
   * drafted this clear as flag-gated too, which leaked a manually-added trail across requests on
   * a reused thread whenever {@code trackBreadcrumbs} happened to be off; fixed there, and built
   * correctly here from the start, by keeping only the fresh-buffer start flag-gated and never the
   * clear. Tomcat/Jetty reuse worker threads across requests the same way Puma does, so leaving
   * this set would leak one request's trail into a later, unrelated request on the same thread.
   */
  public static void endBreadcrumbTrail() {
    breadcrumbs.remove();
  }

  /**
   * Lazily starts a buffer on the current thread if none exists yet (Sidekiq-equivalent
   * background workers and a bare {@code main()} never run through {@code
   * ForgeOpsTrackerBreadcrumbContextFilter} at all), the same "works standalone" fallback {@link
   * #addBreadcrumb} needs. Never used for the read-only path {@link #captureException} takes: see
   * {@link #currentBreadcrumbs()}, which deliberately does not create a buffer just because an
   * error happened to be reported with none.
   */
  private static BreadcrumbBuffer breadcrumbBuffer() {
    BreadcrumbBuffer buffer = breadcrumbs.get();
    if (buffer == null) {
      buffer = new BreadcrumbBuffer(configuration());
      breadcrumbs.set(buffer);
    }
    return buffer;
  }

  private static List<Map<String, Object>> currentBreadcrumbs() {
    BreadcrumbBuffer buffer = breadcrumbs.get();
    return buffer == null ? List.of() : buffer.all();
  }

  /**
   * The two metric buffers, created together on first use: independent of each other (a script may
   * only ever call one), but cheap enough that creating both is simpler than tracking which.
   */
  private static synchronized void ensureMetricBuffers() {
    if (metricBuffer == null) {
      Configuration config = configuration();
      Client client = new Client(config);
      metricBuffer = new MetricBuffer(config, config::getMetricFlushInterval,
          entries -> client.outcome(() -> client.deliverMetrics(entries)));
      infrastructureMetricBuffer = new MetricBuffer(config, config::getInfrastructureMetricFlushInterval,
          entries -> client.outcome(() -> client.deliverInfrastructureMetrics(entries)));
    }
  }

  /**
   * Records a named business metric (a signup, a payment, anything you want to name) with a value of
   * 1, a bare counter: buffered and flushed periodically as one batch rather than one network call
   * per capture. A no-op when the client isn't enabled (no DSN, or this environment isn't in the
   * enabled set).
   */
  public static void captureMetric(String name) {
    captureMetric(name, 1.0);
  }

  /**
   * Records a named business metric with a real magnitude ({@code captureMetric("payment", 49.0)});
   * it may be negative (a refund). A NaN or infinite value is dropped. See {@link #captureMetric(String)}.
   */
  public static void captureMetric(String name, double value) {
    Configuration config = configuration();
    if (!config.isEnabled()) {
      return;
    }
    ensureMetricBuffers();
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("metric_name", name);
    entry.put("value", value);
    entry.put("environment", config.getEnvironment());
    entry.put("release", config.getRelease());
    metricBuffer.record(entry);
  }

  /**
   * Records one infrastructure reading (CPU, memory, disk, anything else a program of yours reads)
   * from one of your own hosts, with {@code hostname} defaulting to
   * {@link Configuration#getServerName()}. Same buffered-batch delivery and no-op-when-disabled
   * contract as {@link #captureMetric}. A JVM shutdown hook flushes what is left when the program
   * exits normally, so a short-lived cron job needs nothing more; call {@link #flushMetrics()} if it
   * ends with {@code Runtime.halt}.
   */
  public static void captureInfrastructureMetric(String name, double value) {
    captureInfrastructureMetric(name, value, null);
  }

  public static void captureInfrastructureMetric(String name, double value, String hostname) {
    Configuration config = configuration();
    if (!config.isEnabled()) {
      return;
    }
    ensureMetricBuffers();
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("metric_name", name);
    entry.put("value", value);
    entry.put("hostname", hostname != null ? hostname : config.getServerName());
    infrastructureMetricBuffer.record(entry);
  }

  /**
   * Delivers every buffered metric and infrastructure reading right now, instead of waiting for the
   * next flush interval.
   */
  public static void flushMetrics() {
    MetricBuffer custom = metricBuffer;
    MetricBuffer infrastructure = infrastructureMetricBuffer;
    if (custom != null) {
      custom.flush();
    }
    if (infrastructure != null) {
      infrastructure.flush();
    }
  }

  /**
   * Internal; called by the servlet integration when a request starts, before {@link #beginTrace},
   * so the trace and every error reported during the request share one trace id. Pair with {@link
   * #exitRequest} in a {@code finally}: servlet containers reuse worker threads across requests.
   */
  public static void enterRequest(RequestState state) {
    requestState.set(state);
  }

  /** Internal; clears whatever {@link #enterRequest} set on this thread. */
  public static void exitRequest() {
    requestState.remove();
  }

  /** Internal; the current thread's request, or {@code null} outside one. */
  public static RequestState currentRequestState() {
    return requestState.get();
  }

  /**
   * The current trace's id (32 lowercase hex characters): the web request's own when inside one,
   * even with {@code trackTracing} off, or {@code null} outside any trace. Handy for your own logs.
   */
  public static String currentTraceId() {
    RequestState state = requestState.get();
    if (state != null) {
      return state.traceId();
    }
    SpanBuffer buffer = spanBuffer.get();
    return buffer == null ? null : buffer.traceId();
  }

  /**
   * Internal; called by the servlet performance-tracking filter at the start of a request. Takes
   * its trace id and remote parent span from the current {@link RequestState} when there is one
   * (so a request that arrived with a {@code traceparent} header continues that trace), and
   * generates a fresh trace id otherwise. A no-op when {@code trackTracing} is off or the client
   * isn't enabled.
   */
  public static void beginTrace() {
    Configuration config = configuration();
    if (!config.isTrackTracing() || !config.isEnabled()) {
      return;
    }
    RequestState state = requestState.get();
    spanBuffer.set(
        state == null ? new SpanBuffer(config) : new SpanBuffer(config, state.traceId(), state.parentSpanId()));
  }

  /**
   * Internal; ends the current thread's trace, sending it when the root took at least {@code
   * traceCaptureThreshold}, or however fast it was when the current request errored (an unhandled
   * exception on its way out, or anything reported with {@link #captureException} during it).
   * Always clears the thread's buffer.
   */
  public static void finishTrace(String rootName, Instant startedAt, double durationMs) {
    SpanBuffer buffer = spanBuffer.get();
    spanBuffer.remove();
    if (buffer == null) {
      return;
    }
    RequestState state = requestState.get();
    boolean errored = state != null && state.isErrored();
    Map<String, Object> payload = buffer.finishTrace(rootName, startedAt, durationMs, errored);
    if (payload != null) {
      spanQueue().push(payload);
    }
  }

  private static synchronized SpanQueue spanQueue() {
    if (spanQueue == null) {
      spanQueue = new SpanQueue(configuration());
    }
    return spanQueue;
  }

  /**
   * Times {@code work} as a child span of whatever span is open on this thread (or of the
   * request's root). Outside a trace it just runs {@code work}. Records even when {@code work}
   * throws, and rethrows unchanged.
   */
  public static <T> T span(String name, String kind, Map<String, Object> data, Callable<T> work) throws Exception {
    SpanBuffer buffer = spanBuffer.get();
    if (buffer == null) {
      return work.call();
    }
    String id = buffer.open();
    Instant startedAt = Instant.now();
    long start = System.nanoTime();
    try {
      return work.call();
    } finally {
      buffer.finish(id, name, kind == null ? "service" : kind, startedAt, (System.nanoTime() - start) / 1_000_000.0,
          data);
    }
  }

  public static <T> T span(String name, Callable<T> work) throws Exception {
    return span(name, "service", null, work);
  }

  /**
   * Times a database query as a {@code database} span carrying its SQL, so ForgeOps can show
   * which statement a slow request spent its time in:
   *
   * <pre>{@code
   * List<Order> orders = ForgeOpsTracker.databaseSpan("Load orders", sql, "postgresql",
   *     () -> jdbc.query(sql, mapper, customerId));
   * }</pre>
   *
   * <p>The statement is masked (every string and number replaced by {@code ?}) and cut to 4000
   * characters before it's stored, then sent as {@code db.statement}; {@code dbSystem}
   * ({@code "postgresql"}, {@code "mysql"}, {@code "sqlite"}, {@code "mssql"}, {@code "oracle"}, or
   * any other name; may be null) goes out lowercased as {@code db.system}. Bind values are never
   * taken. Outside a trace it just runs {@code work}.
   */
  public static <T> T databaseSpan(String name, String statement, String dbSystem, Callable<T> work)
      throws Exception {
    return span(name, "database", databaseSpanData(statement, dbSystem), work);
  }

  /** {@link #recordSpan} for a query you timed yourself, carrying its SQL as {@link #databaseSpan} does. */
  public static void recordDatabaseSpan(String name, String statement, String dbSystem, Instant startedAt,
      double durationMs) {
    recordSpan(name, "database", startedAt, durationMs, databaseSpanData(statement, dbSystem));
  }

  /**
   * The data map {@link #databaseSpan} records: {@code db.statement} and {@code db.system}, each
   * left out when null or blank. Add your own keys to it and pass it to {@link #span} or
   * {@link #recordSpan} with kind {@code "database"} if you prefer; a {@code db.statement} on any
   * database span is masked when the span is recorded, however the map was built.
   */
  public static Map<String, Object> databaseSpanData(String statement, String dbSystem) {
    Map<String, Object> data = new LinkedHashMap<>();
    if (statement != null && !statement.isBlank()) {
      data.put(SpanBuffer.DB_STATEMENT, statement);
    }
    if (dbSystem != null && !dbSystem.isBlank()) {
      data.put(SpanBuffer.DB_SYSTEM, dbSystem.strip().toLowerCase(Locale.ROOT));
    }
    return data;
  }

  /**
   * The call {@link #httpSpan} makes, handed the headers to add to the outgoing request: either
   * empty, or a W3C {@code traceparent} whose parent id is the call's own span.
   */
  @FunctionalInterface
  public interface HttpCall<T> {
    T call(Map<String, String> headers) throws Exception;
  }

  /**
   * Times an outgoing HTTP call as an {@code http} span named {@code "<METHOD> <host>"} (never the
   * path or query, which can carry ids or tokens) and hands {@code call} the headers to add to that
   * request: a W3C {@code traceparent} whose parent id is this span's own id, so the called
   * service's root span nests under it when it continues the trace. This client doesn't instrument
   * any HTTP client itself, so add them however yours does:
   *
   * <pre>{@code
   * HttpResponse<String> response = ForgeOpsTracker.httpSpan("POST", uri, headers -> {
   *   HttpRequest.Builder request = HttpRequest.newBuilder(uri).POST(body);
   *   headers.forEach(request::header);
   *   return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
   * });
   * }</pre>
   *
   * <p>The map is empty outside a request (or other trace), when {@link
   * Configuration#isPropagateTraces} is off, or when the host isn't in {@link
   * Configuration#getTracePropagationTargets}. Inside a request it carries the request's trace id
   * even with {@code trackTracing} off; the span itself is only recorded while a trace is being
   * recorded. Recorded even when {@code call} throws, which is rethrown unchanged.
   */
  public static <T> T httpSpan(String method, URI url, Map<String, Object> data, HttpCall<T> call)
      throws Exception {
    String host = url == null ? null : url.getHost();
    String traceId = currentTraceId();
    if (traceId == null) {
      return call.call(Map.of());
    }
    String spanId = TraceParent.generateSpanId();
    Map<String, String> headers = configuration().shouldPropagateTrace(host)
        ? Map.of(TraceParent.HEADER, TraceParent.build(traceId, spanId))
        : Map.of();
    SpanBuffer buffer = spanBuffer.get();
    if (buffer == null) {
      return call.call(headers);
    }
    buffer.open(spanId);
    Instant startedAt = Instant.now();
    long start = System.nanoTime();
    try {
      return call.call(headers);
    } finally {
      String name =
          (method == null ? "GET" : method.toUpperCase(Locale.ROOT)) + " " + (host == null ? "unknown" : host);
      buffer.finish(spanId, name, "http", startedAt, (System.nanoTime() - start) / 1_000_000.0, data);
    }
  }

  public static <T> T httpSpan(String method, URI url, HttpCall<T> call) throws Exception {
    return httpSpan(method, url, null, call);
  }

  /** Same as the {@link URI} overload; a {@code url} that doesn't parse is treated as having no host. */
  public static <T> T httpSpan(String method, String url, HttpCall<T> call) throws Exception {
    return httpSpan(method, parseUri(url), null, call);
  }

  private static URI parseUri(String url) {
    if (url == null) {
      return null;
    }
    try {
      return new URI(url);
    } catch (URISyntaxException e) {
      return null;
    }
  }

  /** Records an already-timed span under the current one; a no-op outside a trace. */
  public static void recordSpan(String name, String kind, Instant startedAt, double durationMs,
      Map<String, Object> data) {
    SpanBuffer buffer = spanBuffer.get();
    if (buffer != null) {
      buffer.recordLeaf(name, kind == null ? "service" : kind, startedAt, durationMs, data);
    }
  }

  private static synchronized void installUncaughtExceptionHandlerChain() {
    if (installedHandler) {
      return;
    }
    installedHandler = true;
    previousHandler = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
      captureException(error, Map.of("thread", thread.getName()));
      if (previousHandler != null) {
        previousHandler.uncaughtException(thread, error);
      } else {
        System.err.println("Exception in thread \"" + thread.getName() + "\" " + stackTraceString(error));
      }
    });
  }

  private static String stackTraceString(Throwable error) {
    java.io.StringWriter writer = new java.io.StringWriter();
    error.printStackTrace(new java.io.PrintWriter(writer));
    return writer.toString().stripTrailing();
  }

  /**
   * Test-only: discards all configured/installed state so each test starts clean, restoring
   * whatever default uncaught-exception handler was in place before {@link #init} was ever
   * called. Public (not package-private) so a consuming app's own tests can reset this shared
   * static state between cases too: the same {@code _resetForTesting()} convention the Python
   * and Node clients use, just without the leading underscore Java has no equivalent for.
   */
  public static synchronized void resetForTesting() {
    if (installedHandler) {
      Thread.setDefaultUncaughtExceptionHandler(previousHandler);
    }
    if (sessionFlusher != null) {
      sessionFlusher.shutdown();
    }
    if (performanceFlusher != null) {
      performanceFlusher.shutdown();
    }
    if (spanQueue != null) {
      spanQueue.shutdown();
    }
    if (metricBuffer != null) {
      metricBuffer.shutdown();
      infrastructureMetricBuffer.shutdown();
    }
    metricBuffer = null;
    infrastructureMetricBuffer = null;
    spanQueue = null;
    configuration = null;
    reporter = null;
    sessionFlusher = null;
    performanceFlusher = null;
    previousHandler = null;
    installedHandler = false;
    changeSnapshotSent.set(false);
    currentUser.remove();
    breadcrumbs.remove();
    spanBuffer.remove();
    requestState.remove();
  }
}
