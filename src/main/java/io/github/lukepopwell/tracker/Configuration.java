package io.github.lukepopwell.tracker;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * A single DSN string carries both the ingestion URL and the project's API key:
 * {@code https://<api_key>@host/api/v1/events}. Mirrors {@code ForgeOpsTracker::Configuration} in
 * the Rails client gem: see that gem's README for the shared design rationale.
 */
public final class Configuration {
  private String dsn = System.getenv("FORGE_OPS_DSN");
  /**
   * {@code FORGE_OPS_ENVIRONMENT} when set, otherwise {@code "production"}: a process given a DSN
   * and nothing else is assumed to be one you want errors from. Set {@code
   * FORGE_OPS_ENVIRONMENT=development} (or call {@link #setEnvironment}) where it shouldn't send.
   */
  private String environment = resolveEnvironment(System::getenv);
  private String release = System.getenv("FORGE_OPS_RELEASE");
  private String serverName = safeHostname();

  /**
   * Package prefix (e.g. {@code "com.acme.myapp"}) used to mark backtrace frames as {@code
   * in_app}. Unset by default: deliberately mirrors the Rails gem's behavior when {@code
   * app_root} is unset (every frame reported as not-in-app, the safe default), since there's no
   * safe way to guess a caller's top-level package automatically.
   *
   * <p>Unlike the Ruby/Python/PHP/Node clients, this can't be file-path-based: a {@link
   * StackTraceElement#getFileName()} is always a bare file name ({@code "MyClass.java"}), never a
   * full path, verified directly rather than assumed: the same reason the .NET client uses
   * assembly identity instead of a path comparison. A package-name prefix is this client's
   * equivalent of the .NET client's assembly check.
   */
  private String appPackagePrefix;

  private Set<String> enabledEnvironments = new LinkedHashSet<>(List.of("production", "staging"));
  private int queueSize = 1000;
  private Duration connectTimeout = Duration.ofSeconds(2);
  private Duration readTimeout = Duration.ofSeconds(2);
  private boolean scrubPii = true;

  /**
   * Whether {@link EventBuilder} reads a few lines of source off disk around each in-app frame's
   * culprit line (see {@code EventBuilder#attachSourceContext}). Defaults on so a snippet shows
   * up without any extra setup, but this flag alone isn't the real protection against literal
   * source code leaving this process: every ForgeOps project has its own server-side setting
   * that's the durable, enforced switch, since it applies no matter what this flag happens to be
   * set to on any given deployment. Turn this off here too if this host app should never even
   * attempt that disk read in the first place.
   */
  private boolean captureSourceContext = true;

  /**
   * When an error comes from a database call (Hibernate's {@code JDBCException}, Spring's {@code
   * BadSqlGrammarException}, and anything that wraps one), send the names of the stored
   * procedure, table and view its SQL touched, so an issue says where to start looking. Names are
   * identifiers, never values, which is why this defaults on. {@link #captureSqlStatement} is the
   * separate, opt-in step of also sending the statement itself, with every string and number
   * replaced by {@code ?}; off by default because even a masked statement describes your schema,
   * and ForgeOps' own per-project setting is what durably governs whether the server stores it.
   */
  private boolean captureSqlObjects = true;

  private boolean captureSqlStatement = false;

  /** Called with a debug-level message on delivery failures; {@code null} (the default) discards them. */
  private Consumer<String> logger;

  /**
   * Whether the servlet session-tracking filter counts every request as a session (crash-free
   * unless an unhandled exception actually escaped it, or the response ended up with a 5xx
   * status) and periodically reports an aggregate crash-free rate. On by default, the same "on
   * unless you turn it off" posture error tracking itself already has.
   */
  private boolean trackSessions = true;
  /** Interval between aggregate session reports; requests are counted in-process and flushed as
   * one small report on this interval, not one network call per request. */
  private Duration sessionFlushInterval = Duration.ofSeconds(60);

  /**
   * Whether the servlet performance-tracking filter times every request and periodically
   * reports an aggregate per transaction. On by default, the same posture as {@code
   * trackSessions} above.
   */
  private boolean trackPerformance = true;
  /**
   * Intervals between flushes of the buffered {@code captureMetric}/{@code captureInfrastructureMetric}
   * entries (see {@link MetricBuffer}). There is no {@code trackMetrics} flag the way
   * {@code trackPerformance} has one: these are explicit calls the host app's own code makes, not
   * automatic instrumentation, so there is nothing to turn off that simply not calling them doesn't
   * already do.
   */
  private Duration metricFlushInterval = Duration.ofSeconds(60);
  private Duration infrastructureMetricFlushInterval = Duration.ofSeconds(60);

  /** Interval between aggregate performance reports; same "counted in-process, flushed as one
   * small batch on this interval" reasoning as {@code sessionFlushInterval} above. */
  private Duration performanceFlushInterval = Duration.ofSeconds(60);

  /**
   * Whether the servlet performance-tracking filter records a trace per request and reports it
   * (when slow, or when the request errored) to {@code /spans}. {@code ForgeOpsTracker#span} and
   * {@code #recordSpan} only record inside a trace, so turning this off stops every span. Every
   * request still gets a trace id with this off: it goes on errors reported during the request and
   * on the {@code traceparent} header {@code ForgeOpsTracker#httpSpan} hands out (see {@link
   * #propagateTraces}), since that id is also what links an error here to one in another service.
   */
  private boolean trackTracing = true;
  /** A trace is only sent when its root span took at least this long (or its request errored). */
  private Duration traceCaptureThreshold = Duration.ofSeconds(1);

  /**
   * Whether {@code ForgeOpsTracker#httpSpan} hands its call a W3C {@code traceparent} header for
   * the outgoing request, so the service being called continues this trace. On by default,
   * matching {@code gems/forge_ops_tracker}: the header carries the trace id that links an error
   * here to an error there, which is useful with or without spans, so it goes out even with {@link
   * #trackTracing} off.
   */
  private boolean propagateTraces = true;

  /**
   * Which hosts get that header. {@code null} (the default) means every host. Otherwise a list of
   * host {@link String}s, each matching that host and its subdomains on a dot boundary ({@code
   * "example.com"} matches {@code "api.example.com"}, never {@code "badexample.com"}; case and a
   * leading dot are ignored), and/or {@link java.util.regex.Pattern}s searched for anywhere in the
   * lowercased host, so anchor them yourself. Anything else in the list matches nothing. Useful for
   * a third-party API that rejects unknown headers, or that shouldn't learn your trace ids at all.
   */
  private List<Object> tracePropagationTargets;

  /**
   * Whether the servlet performance-tracking filter's automatic controller breadcrumb is
   * recorded, and whether {@link EventBuilder} attaches whatever trail accumulated on the
   * current thread to a reported error at all. On by default, the same posture as {@code
   * trackPerformance} above; gated independently of it, same as the Ruby gem's own {@code
   * track_breadcrumbs}, since an app could want the trail without the timing data, or vice versa.
   * Never gates {@code ForgeOpsTracker#addBreadcrumb}: the manual API always works, regardless of
   * this flag, the same "off just disables the automatic sources" contract {@code
   * ForgeOpsTracker.add_breadcrumb}'s own doc comment documents in the Ruby gem.
   */
  private boolean trackBreadcrumbs = true;
  /**
   * Oldest entry dropped once this many have accumulated on one thread's trail: the same bounded
   * ring buffer reasoning {@code gems/forge_ops_tracker/lib/forge_ops_tracker/configuration.rb}'s
   * own {@code max_breadcrumbs} documents, so a very long-running request doesn't grow the trail
   * (and the payload it rides in) without bound.
   */
  private int maxBreadcrumbs = 30;

  /**
   * Whether {@link ForgeOpsTracker#init} sends one change snapshot per process (the Java runtime
   * version, plus environment variable names when {@link #trackEnvVarNames} is on) so ForgeOps can
   * show what changed between deploys. The server diffs it against the previous snapshot for the
   * same project and environment; this client keeps no state of its own.
   */
  private boolean detectChanges = true;

  /**
   * Whether that snapshot also lists environment variable names (never values), minus the
   * host-specific ones {@link ChangeTracker#isNoiseEnvVar} drops. Off by default: a variable's name
   * alone can still say more about a deployment than some teams want to send.
   */
  private boolean trackEnvVarNames = false;

  /**
   * Strict privacy, for apps that can't let personal or health data leave their own servers:
   * events carry only structured facts (exception class, stack frames, release, environment,
   * where it happened), never free text. The message is replaced by {@code message_fingerprint}
   * (see {@link StrictPrivacy#messageFingerprint}), breadcrumbs aren't recorded or sent, {@code
   * endpoint}, {@code sql_statement} and source context aren't sent, the user is sent as its id
   * only, and context and tags keep only the {@link #privacyAllowedFields} with plain values.
   * Spans keep only their method and host for outbound calls, and only {@code db.statement} and
   * {@code db.system} as data. Off by default; {@code FORGE_OPS_STRICT_PRIVACY=true} turns it on.
   */
  private boolean strictPrivacy = resolveStrictPrivacy(System::getenv);

  /**
   * The context and tag keys strict privacy lets through anyway (your own error codes, a feature
   * name), when their value is a string, number or boolean. Empty by default; {@code
   * FORGE_OPS_PRIVACY_ALLOWED_FIELDS=error_code,feature} sets it from the environment.
   */
  private Set<String> privacyAllowedFields = resolvePrivacyAllowedFields(System::getenv);

  public String getDsn() {
    return dsn;
  }

  public void setDsn(String dsn) {
    this.dsn = dsn;
    this.parsedDsn = null;
  }

  public String getEnvironment() {
    return environment;
  }

  public void setEnvironment(String environment) {
    this.environment = environment;
  }

  public String getRelease() {
    return release;
  }

  public void setRelease(String release) {
    this.release = release;
  }

  public String getServerName() {
    return serverName;
  }

  public void setServerName(String serverName) {
    this.serverName = serverName;
  }

  public String getAppPackagePrefix() {
    return appPackagePrefix;
  }

  public void setAppPackagePrefix(String appPackagePrefix) {
    this.appPackagePrefix = appPackagePrefix;
  }

  public Set<String> getEnabledEnvironments() {
    return enabledEnvironments;
  }

  public void setEnabledEnvironments(Set<String> enabledEnvironments) {
    this.enabledEnvironments = enabledEnvironments;
  }

  public int getQueueSize() {
    return queueSize;
  }

  public void setQueueSize(int queueSize) {
    this.queueSize = queueSize;
  }

  public Duration getConnectTimeout() {
    return connectTimeout;
  }

  public void setConnectTimeout(Duration connectTimeout) {
    this.connectTimeout = connectTimeout;
  }

  public Duration getReadTimeout() {
    return readTimeout;
  }

  public void setReadTimeout(Duration readTimeout) {
    this.readTimeout = readTimeout;
  }

  public boolean isScrubPii() {
    return scrubPii;
  }

  public void setScrubPii(boolean scrubPii) {
    this.scrubPii = scrubPii;
  }

  public boolean isCaptureSourceContext() {
    return captureSourceContext;
  }

  public void setCaptureSourceContext(boolean captureSourceContext) {
    this.captureSourceContext = captureSourceContext;
  }

  public boolean isCaptureSqlObjects() {
    return captureSqlObjects;
  }

  public void setCaptureSqlObjects(boolean captureSqlObjects) {
    this.captureSqlObjects = captureSqlObjects;
  }

  public boolean isCaptureSqlStatement() {
    return captureSqlStatement;
  }

  public void setCaptureSqlStatement(boolean captureSqlStatement) {
    this.captureSqlStatement = captureSqlStatement;
  }

  public Consumer<String> getLogger() {
    return logger;
  }

  public void setLogger(Consumer<String> logger) {
    this.logger = logger;
  }

  public boolean isTrackSessions() {
    return trackSessions;
  }

  public void setTrackSessions(boolean trackSessions) {
    this.trackSessions = trackSessions;
  }

  public Duration getSessionFlushInterval() {
    return sessionFlushInterval;
  }

  public void setSessionFlushInterval(Duration sessionFlushInterval) {
    this.sessionFlushInterval = sessionFlushInterval;
  }

  public boolean isTrackPerformance() {
    return trackPerformance;
  }

  public void setTrackPerformance(boolean trackPerformance) {
    this.trackPerformance = trackPerformance;
  }

  public boolean isTrackTracing() {
    return trackTracing;
  }

  public void setTrackTracing(boolean trackTracing) {
    this.trackTracing = trackTracing;
  }

  public Duration getTraceCaptureThreshold() {
    return traceCaptureThreshold;
  }

  public void setTraceCaptureThreshold(Duration traceCaptureThreshold) {
    this.traceCaptureThreshold = traceCaptureThreshold;
  }

  public boolean isPropagateTraces() {
    return propagateTraces;
  }

  public void setPropagateTraces(boolean propagateTraces) {
    this.propagateTraces = propagateTraces;
  }

  public List<Object> getTracePropagationTargets() {
    return tracePropagationTargets;
  }

  public void setTracePropagationTargets(List<?> tracePropagationTargets) {
    this.tracePropagationTargets = tracePropagationTargets == null ? null : new ArrayList<>(tracePropagationTargets);
  }

  /**
   * Whether an outgoing call to {@code host} should carry a {@code traceparent} header;
   * case-insensitive, since hostnames are.
   */
  public boolean shouldPropagateTrace(String host) {
    if (!propagateTraces) {
      return false;
    }
    List<Object> targets = tracePropagationTargets;
    if (targets == null) {
      return true;
    }
    String normalized = host == null ? "" : host.toLowerCase(Locale.ROOT);
    for (Object target : targets) {
      if (target instanceof Pattern pattern) {
        if (pattern.matcher(normalized).find()) {
          return true;
        }
      } else if (target instanceof String string) {
        String domain = string.toLowerCase(Locale.ROOT);
        if (domain.startsWith(".")) {
          domain = domain.substring(1);
        }
        if (!domain.isEmpty() && (normalized.equals(domain) || normalized.endsWith("." + domain))) {
          return true;
        }
      }
    }
    return false;
  }

  public Duration getMetricFlushInterval() {
    return metricFlushInterval;
  }

  public void setMetricFlushInterval(Duration metricFlushInterval) {
    this.metricFlushInterval = metricFlushInterval;
  }

  public Duration getInfrastructureMetricFlushInterval() {
    return infrastructureMetricFlushInterval;
  }

  public void setInfrastructureMetricFlushInterval(Duration infrastructureMetricFlushInterval) {
    this.infrastructureMetricFlushInterval = infrastructureMetricFlushInterval;
  }

  public Duration getPerformanceFlushInterval() {
    return performanceFlushInterval;
  }

  public void setPerformanceFlushInterval(Duration performanceFlushInterval) {
    this.performanceFlushInterval = performanceFlushInterval;
  }

  public boolean isTrackBreadcrumbs() {
    return trackBreadcrumbs;
  }

  public void setTrackBreadcrumbs(boolean trackBreadcrumbs) {
    this.trackBreadcrumbs = trackBreadcrumbs;
  }

  public int getMaxBreadcrumbs() {
    return maxBreadcrumbs;
  }

  public void setMaxBreadcrumbs(int maxBreadcrumbs) {
    this.maxBreadcrumbs = maxBreadcrumbs;
  }

  public boolean isDetectChanges() {
    return detectChanges;
  }

  public void setDetectChanges(boolean detectChanges) {
    this.detectChanges = detectChanges;
  }

  public boolean isTrackEnvVarNames() {
    return trackEnvVarNames;
  }

  public void setTrackEnvVarNames(boolean trackEnvVarNames) {
    this.trackEnvVarNames = trackEnvVarNames;
  }

  public boolean isStrictPrivacy() {
    return strictPrivacy;
  }

  public void setStrictPrivacy(boolean strictPrivacy) {
    this.strictPrivacy = strictPrivacy;
  }

  public Set<String> getPrivacyAllowedFields() {
    return privacyAllowedFields;
  }

  public void setPrivacyAllowedFields(Collection<String> privacyAllowedFields) {
    this.privacyAllowedFields =
        privacyAllowedFields == null ? new LinkedHashSet<>() : new LinkedHashSet<>(privacyAllowedFields);
  }

  public String getApiKey() {
    URI parsed = parsedDsn();
    return parsed == null ? null : parsed.getUserInfo();
  }

  /** The ingestion URL with credentials stripped out: they travel as the Authorization header instead. */
  public URI getIngestionUri() {
    URI parsed = parsedDsn();
    if (parsed == null) {
      return null;
    }
    try {
      return new URI(parsed.getScheme(), null, parsed.getHost(), parsed.getPort(), parsed.getPath(), null, null);
    } catch (URISyntaxException e) {
      return null;
    }
  }

  /**
   * Same derivation as {@link #getIngestionUri()}, with the trailing {@code /events} swapped for
   * {@code /session_checkins}: one DSN, two endpoints, matching the Ruby gem's own {@code
   * Configuration#session_checkins_uri}.
   */
  public URI getSessionCheckinsUri() {
    URI uri = getIngestionUri();
    if (uri == null) {
      return null;
    }
    String path = uri.getPath();
    if (path != null && path.endsWith("/events")) {
      try {
        return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
            path.substring(0, path.length() - "/events".length()) + "/session_checkins", null, null);
      } catch (URISyntaxException e) {
        return null;
      }
    }
    return uri;
  }

  /**
   * Same derivation again, swapping the trailing {@code /events} for {@code /performance_samples}.
   */
  public URI getPerformanceSamplesUri() {
    URI uri = getIngestionUri();
    if (uri == null) {
      return null;
    }
    String path = uri.getPath();
    if (path != null && path.endsWith("/events")) {
      try {
        return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
            path.substring(0, path.length() - "/events".length()) + "/performance_samples", null, null);
      } catch (URISyntaxException e) {
        return null;
      }
    }
    return uri;
  }

  /** Same derivation again, swapping the trailing {@code /events} for {@code /custom_metrics}. */
  public URI getCustomMetricsUri() {
    return swapEventsSuffix("/custom_metrics");
  }

  /** Same derivation again, swapping the trailing {@code /events} for {@code /infrastructure_metrics}. */
  public URI getInfrastructureMetricsUri() {
    return swapEventsSuffix("/infrastructure_metrics");
  }

  /** Same derivation again, swapping the trailing {@code /events} for {@code /changes}. */
  public URI getChangesUri() {
    return swapEventsSuffix("/changes");
  }

  /** Same derivation again, swapping the trailing {@code /events} for {@code /change_snapshots}. */
  public URI getChangeSnapshotsUri() {
    return swapEventsSuffix("/change_snapshots");
  }

  private URI swapEventsSuffix(String replacement) {
    URI uri = getIngestionUri();
    if (uri == null) {
      return null;
    }
    String path = uri.getPath();
    if (path != null && path.endsWith("/events")) {
      try {
        return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
            path.substring(0, path.length() - "/events".length()) + replacement, null, null);
      } catch (URISyntaxException e) {
        return null;
      }
    }
    return uri;
  }

  /** Same derivation again, swapping the trailing {@code /events} for {@code /spans}. */
  public URI getSpansUri() {
    URI uri = getIngestionUri();
    if (uri == null) {
      return null;
    }
    String path = uri.getPath();
    if (path != null && path.endsWith("/events")) {
      try {
        return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
            path.substring(0, path.length() - "/events".length()) + "/spans", null, null);
      } catch (URISyntaxException e) {
        return null;
      }
    }
    return uri;
  }

  public boolean isEnabled() {
    return !blank(dsn) && !blank(getApiKey()) && enabledEnvironments.contains(environment);
  }

  /**
   * The environment a fresh {@code Configuration} starts with, read through {@code getenv}: {@code
   * FORGE_OPS_ENVIRONMENT} when set and not blank, otherwise {@code "production"}. No framework
   * variable is consulted: Spring's {@code SPRING_PROFILES_ACTIVE}, the nearest candidate, is a
   * list of profiles rather than an environment name.
   */
  static String resolveEnvironment(Function<String, String> getenv) {
    return firstNonBlank(getenv.apply("FORGE_OPS_ENVIRONMENT"), "production");
  }

  /** {@code FORGE_OPS_STRICT_PRIVACY}: {@code true} or {@code 1} (any case) turns strict privacy on. */
  static boolean resolveStrictPrivacy(Function<String, String> getenv) {
    String value = getenv.apply("FORGE_OPS_STRICT_PRIVACY");
    return value != null && (value.strip().equalsIgnoreCase("true") || value.strip().equals("1"));
  }

  /** {@code FORGE_OPS_PRIVACY_ALLOWED_FIELDS}: comma separated, blanks around each name ignored. */
  static Set<String> resolvePrivacyAllowedFields(Function<String, String> getenv) {
    Set<String> fields = new LinkedHashSet<>();
    String value = getenv.apply("FORGE_OPS_PRIVACY_ALLOWED_FIELDS");
    if (value != null) {
      for (String field : value.split(",")) {
        if (!field.isBlank()) {
          fields.add(field.strip());
        }
      }
    }
    return fields;
  }

  /**
   * The explanation {@link ForgeOpsTracker#init} prints when this configuration has a DSN but its
   * environment isn't one of {@link #getEnabledEnvironments}, so nothing will be sent; {@code null}
   * when there's nothing to warn about (no DSN, or the environment is enabled).
   */
  public String notSendingWarning() {
    if (blank(dsn) || blank(getApiKey()) || enabledEnvironments.contains(environment)) {
      return null;
    }
    String enabled = enabledEnvironments.isEmpty()
        ? "no environments are enabled"
        : "only " + String.join(", ", enabledEnvironments) + " are enabled";
    return "Not sending: this environment is \"" + environment + "\", and " + enabled
        + ". Set FORGE_OPS_ENVIRONMENT=production (or add \"" + environment
        + "\" to the enabled environments) to send from here.";
  }

  public void log(String message) {
    if (logger != null) {
      logger.accept("[ForgeOpsTracker] " + message);
    }
  }

  private URI parsedDsn;
  private boolean parsedDsnAttempted;

  private URI parsedDsn() {
    if (blank(dsn)) {
      return null;
    }
    if (!parsedDsnAttempted) {
      parsedDsnAttempted = true;
      try {
        parsedDsn = new URI(dsn);
      } catch (URISyntaxException e) {
        // A malformed DSN is a configuration mistake, not something that should ever crash the
        // host app: same "never throw back into the caller" guarantee as every other client.
        // Verified directly: unlike Ruby's URI.parse, Python's urlsplit, and PHP's parse_url (all
        // lenient), java.net.URI genuinely throws a checked exception on malformed input.
        parsedDsn = null;
      }
    }
    return parsedDsn;
  }

  private static boolean blank(String value) {
    return value == null || value.strip().isEmpty();
  }

  private static String firstNonBlank(String value, String fallback) {
    return blank(value) ? fallback : value;
  }

  private static String safeHostname() {
    try {
      return java.net.InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return null;
    }
  }
}
