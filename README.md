# io.github.luke-popwell:tracker

Java error reporting client for [ForgeOps](https://getforgeops.net).
Requires Java 17+. Captures uncaught exceptions automatically through Servlet and Spring MVC
integrations, or a JVM-wide uncaught-exception handler outside a web container, lets you report
caught exceptions explicitly, and scrubs likely personal data before anything leaves the process.

> For health or personal data, turn on [strict privacy](#strict-privacy): the SDK then sends only structured facts about each error (its type, stack trace, release and route), never the message, a raw path, breadcrumbs, or context you haven't allowed.

## Installation

```xml
<!-- your pom.xml -->
<dependency>
  <groupId>io.github.luke-popwell</groupId>
  <artifactId>tracker</artifactId>
  <version>0.16.0</version>
</dependency>
```

The `groupId` has a hyphen; the actual Java package (below) doesn't, since Java identifiers can't
contain one: Sonatype Central's namespace verification is tied to the exact GitHub username
string (`io.github.luke-popwell`), which is what `groupId` has to match, while the package name is
free to be anything valid.

## Configuration

Set a DSN (from a project's settings page in ForgeOps), either via the `FORGE_OPS_DSN`
environment variable or explicitly:

```java
import io.github.lukepopwell.tracker.ForgeOpsTracker;

ForgeOpsTracker.init(config -> {
  config.setDsn("https://<api_key>@getforgeops.net/api/v1/events"); // or leave unset to read FORGE_OPS_DSN
  config.setRelease("1.4.2");
  config.setEnvironment("production");
});
```

Call `init()` once at startup, before handling any requests. `Configuration` is a plain mutable
object: any property can be set inside the configurer lambda.

### Environments

The environment comes from `setEnvironment`, or the `FORGE_OPS_ENVIRONMENT` environment variable,
and is `"production"` when neither is set, so a DSN alone is enough to start sending. Errors are
only sent from the enabled environments (`setEnabledEnvironments`, default `production` and
`staging`). On a machine that shouldn't send, such as a laptop, set
`FORGE_OPS_ENVIRONMENT=development`. When a DSN is configured but the environment isn't enabled,
`init()` says so once, through `setLogger` if you set one and on `stderr` otherwise:

```
[ForgeOps] Not sending: this environment is "development", and only production, staging are enabled. Set FORGE_OPS_ENVIRONMENT=production (or add "development" to the enabled environments) to send from here.
```

### Servlet (any Jakarta Servlet 5+ container)

```java
import io.github.lukepopwell.tracker.integrations.servlet.ForgeOpsTrackerFilter;
import io.github.lukepopwell.tracker.integrations.servlet.ForgeOpsTrackerSessionTrackingFilter;
import io.github.lukepopwell.tracker.integrations.servlet.ForgeOpsTrackerPerformanceTrackingFilter;
import io.github.lukepopwell.tracker.integrations.servlet.ForgeOpsTrackerBreadcrumbContextFilter;

// web.xml, or programmatically via ServletContext#addFilter
new ForgeOpsTrackerFilter(); // register last, closest to your servlet
new ForgeOpsTrackerSessionTrackingFilter(); // order relative to the filter above doesn't matter
new ForgeOpsTrackerPerformanceTrackingFilter(); // order doesn't matter either
new ForgeOpsTrackerBreadcrumbContextFilter(); // order doesn't matter either; see "Breadcrumbs" below
```

Reports any exception that propagates out of the filter chain, then rethrows it unchanged: the
container's own error handling (an `<error-page>` mapping, Spring Boot's `/error` whitelabel page,
whatever else is configured) runs exactly as if this filter weren't installed.

### Spring MVC / Spring Boot

```java
import io.github.lukepopwell.tracker.integrations.spring.ForgeOpsTrackerHandlerExceptionResolver;

@Bean
public ForgeOpsTrackerHandlerExceptionResolver forgeOpsTrackerHandlerExceptionResolver() {
  return new ForgeOpsTrackerHandlerExceptionResolver();
}
```

Reports every exception a Spring MVC handler raises, then always returns `null` so Spring's own
resolution chain (a `@ExceptionHandler`, `DefaultErrorAttributes`, the whitelabel error page, or
anything else registered) still runs exactly as if this resolver weren't registered. Ordered at
`Ordered.HIGHEST_PRECEDENCE` deliberately: Spring stops at the first resolver that returns a
non-null result, so this one has to run *before* anything that might actually claim the exception,
not after.

`jakarta.servlet-api`, `spring-webmvc`, and `spring-security-core` (the last one only needed for
`ForgeOpsTrackerUserContextInterceptor` below) are all compile-time-only (`provided` scope)
dependencies of this library: never bundled into the jar, since the host application already
supplies its own copy of each at runtime.

Add `ForgeOpsTrackerUserContextInterceptor` (via `WebMvcConfigurer#addInterceptors`) alongside it
for [automatic user identification](#identifying-users) with Spring Security:

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new ForgeOpsTrackerUserContextInterceptor());
  }
}
```

## What gets reported automatically, and what doesn't

**An exception that crashes a request needs no further wiring at all.** Both integrations above
report anything that propagates uncaught out of a servlet/handler, then let the container/framework
handle it exactly as if this client weren't installed.

**An exception your own code catches and handles is different: neither integration ever sees
it**, since it never propagates far enough to reach either hook:

```java
try {
  chargeCard(order);
} catch (PaymentException e) {
  logger.warn("card declined: " + e.getMessage());
  // ForgeOps never sees this: caught locally, never reaches the filter/resolver at all.
}
```

Report it explicitly instead, right at the catch site:

```java
import io.github.lukepopwell.tracker.ForgeOpsTracker;
import java.util.Map;

try {
  chargeCard(order);
} catch (PaymentException e) {
  ForgeOpsTracker.captureException(e, Map.of("orderId", order.getId()));
  logger.warn("card declined: " + e.getMessage());
}
```

### Outside a web request (a CLI tool, a batch job, a background thread)

`init()` also installs a JVM-wide `Thread.UncaughtExceptionHandler` by default (pass `false` as
`init`'s second argument to opt out) covering any thread that dies from an exception nobody caught,
the single most common way a non-web Java process fails silently in production (a log line
nobody's watching, then the thread is just gone).

Verified directly against a real uncaught throw: installing `Thread.setDefaultUncaughtExceptionHandler`
doesn't change whether or how the JVM exits: a thread that dies from an uncaught exception dies
either way. The one real side effect is that it replaces the JVM's own default behavior of printing
`"Exception in thread ..."` plus the stack trace to `stderr`: this client's handler restores
that exactly: it chains to whatever default handler was already installed, if any, and otherwise
reproduces the JVM's own printed-to-stderr output itself. Installing this client changes nothing
observable about an uncaught exception besides also reporting it.

A program that reports an error and then ends needs nothing more either: the first report
registers a JVM shutdown hook that sends whatever is still queued before the JVM exits, whether
`main` returns, calls `System.exit`, or dies from an uncaught exception. It waits at most 5 seconds,
and a program that never reported anything doesn't wait at all. `Runtime.halt` skips shutdown
hooks, so call `ForgeOpsTracker.flush()` first if your program ends that way:

```java
import io.github.lukepopwell.tracker.ForgeOpsTracker;

ForgeOpsTracker.captureException(e);
ForgeOpsTracker.flush(); // sends what's queued, waiting at most 5 seconds; flush(Duration) to choose
Runtime.getRuntime().halt(1);
```

Every failure mode (network errors, timeouts, a full queue, a malformed DSN) is caught and
dropped rather than thrown, so a broken or unreachable tracker can never take down the host app.

## Identifying users

```java
ForgeOpsTracker.captureException(error, Map.of(), Map.of("id", user.getId(), "email", user.getEmail()));
```

Or `setUser(Map<String, Object>)` to attach it for the rest of this thread (a servlet request, a
background worker) rather than passing it to every `captureException()` call by hand, e.g. from
your own filter/interceptor for a plain Servlet app or a non-Spring-Security auth setup:

```java
ForgeOpsTracker.setUser(Map.of("id", request.getUserPrincipal().getName()));
```

A `ThreadLocal`, the direct Java analog to `Thread.current` in `gems/forge_ops_tracker`'s own
equivalent, and the correct isolation boundary for a typical servlet container's
one-thread-per-request model. Passing an empty map (or `null`) clears whatever was set, e.g. at
the end of a request. Shows up on an issue's own detail page, and as its own affected-users count
alongside the regular event count.

**Spring MVC apps using Spring Security get this automatically**: register
`ForgeOpsTrackerUserContextInterceptor` (see the Spring MVC snippet above) and it detects
`SecurityContextHolder`'s current `Authentication` for you. `Authentication.getName()` (the one
thing every implementation genuinely guarantees, inherited from `java.security.Principal`) becomes
`username`; `id`/`email` are read reflectively off `getPrincipal()` (a `getId()`/`getEmail()`
method, if either exists), since Spring Security's core module has no universal claim-style
convention the way ASP.NET Core's `ClaimsPrincipal` does. A no-op for an unauthenticated request
(specifically excluding Spring Security's own `AnonymousAuthenticationToken`, whose
`isAuthenticated()` confirmed directly still returns `true`, not just a plain `null`
`Authentication`) or a custom principal with neither getter. Composes with the manual API above
rather than replacing it: call `setUser()` yourself afterward (e.g. for a custom auth setup this
can't detect, or to override what was auto-detected) and it wins for the rest of that request.

## Breadcrumbs

A trail of what happened right before an error, on by default, no setup needed once
`ForgeOpsTrackerBreadcrumbContextFilter` is registered: one automatic "controller" breadcrumb per
request, recorded by `ForgeOpsTrackerPerformanceTrackingFilter` (this client's only automatic
source: unlike the Ruby gem, there is no query-level/ORM instrumentation here to hang a second one
off of), shows up alongside the error on an issue's own detail page.

```java
ForgeOpsTracker.init(config -> {
  config.setDsn("...");
  config.setTrackBreadcrumbs(false); // opt out of the automatic source entirely
  config.setMaxBreadcrumbs(30); // oldest entry dropped once this many have accumulated on one thread
});
```

Add your own by hand, regardless of whether the automatic source is on:

```java
ForgeOpsTracker.addBreadcrumb("charged card", "billing", "info", Map.of("orderId", order.getId()));

// category, level, and data are all independently optional, cascading to "custom"/"info"/{}:
ForgeOpsTracker.addBreadcrumb("checkpoint");
ForgeOpsTracker.addBreadcrumb("loaded order", "billing");
ForgeOpsTracker.addBreadcrumb("loaded order", "billing", "warning");
```

`level` is one of `"debug"`/`"info"`/`"warning"`/`"error"`, the same four levels the automatic
source itself uses. Works outside a request entirely too (a background worker, a plain `main()`):
the buffer it adds to is created lazily on whatever thread calls it, the same "works standalone, no
specific setup required" shape `setUser` already has.

Each request gets its own fresh, bounded trail (a ring buffer capped at `maxBreadcrumbs`, oldest
entry dropped once full): a `ThreadLocal<BreadcrumbBuffer>` on `ForgeOpsTracker`, the same
isolation boundary `currentUser` already uses, reset to a fresh buffer at the start of every
request by `ForgeOpsTrackerBreadcrumbContextFilter` and cleared again in a `finally`, so one
request's trail never bleeds into another's on a reused Tomcat/Jetty worker thread. Spring MVC
apps need no separate breadcrumb-scoping piece beyond that one filter: a `HandlerInterceptor` or
`HandlerExceptionResolver` always runs from inside `DispatcherServlet`'s own dispatch, which is
itself always invoked from inside this filter's wrapped `chain.doFilter`, all on the same thread.

Unlike the affected user above, a breadcrumb's message/data **is** scrubbed for likely PII:
console-style/request trail entries are exactly the kind of free text (a URL with a token in it, a
value someone typed) the scrubber exists to catch, not a deliberately-structured field the way
`user` is.

## Distributed tracing

A slow request's own breakdown: which database calls, HTTP calls, or pieces of your code the
time went to, shown as a span tree on ForgeOps. On by default. `ForgeOpsTrackerPerformanceTrackingFilter`
starts a trace for every request and, once the request finishes, sends it when the request itself
took at least `traceCaptureThreshold` (1 second by default) or when it errored (see "Following a
request across services" below), so fast, successful requests cost nothing on the wire.

This client has no query-level or HTTP-client instrumentation to hook, so the only automatic span
is the request itself. Add the rest by hand:

```java
Order order = ForgeOpsTracker.span("charge card", "service", Map.of("orderId", id), () -> gateway.charge(id));
ForgeOpsTracker.span("render", () -> view.render()); // kind defaults to "service"

// Something you timed yourself (kind is one of controller/service/database/redis/http/job/other):
ForgeOpsTracker.recordSpan("SELECT orders", "database", startedAt, durationMs, Map.of());
```

`span` nests under whichever span is open on the same thread, records even when the block throws
(rethrowing it unchanged), and simply runs the block outside a request. A trace holds at most 500
spans. Configure with `config.setTrackTracing(false)` and `config.setTraceCaptureThreshold(...)`.

### Database spans with their SQL

JDBC isn't instrumented automatically, so wrap a query in `databaseSpan` and pass its SQL. ForgeOps
then shows which statement a slow request spent its time in:

```java
import io.github.lukepopwell.tracker.ForgeOpsTracker;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

public class OrderRepository {
  private static final String OPEN_ORDERS =
      "SELECT id FROM orders WHERE customer_id = ? AND status = 'open'";

  private final DataSource dataSource;

  public OrderRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public List<Long> openOrderIds(long customerId) throws Exception {
    return ForgeOpsTracker.databaseSpan("Load open orders", OPEN_ORDERS, "postgresql", () -> {
      try (Connection connection = dataSource.getConnection();
          PreparedStatement statement = connection.prepareStatement(OPEN_ORDERS)) {
        statement.setLong(1, customerId);
        List<Long> ids = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery()) {
          while (rows.next()) {
            ids.add(rows.getLong("id"));
          }
        }
        return ids;
      }
    });
  }
}
```

The statement is masked before it's stored: every string and number becomes `?`, so this span
carries `SELECT id FROM orders WHERE customer_id = ? AND status = ?`, and ForgeOps masks it again
on arrival. It's cut to 4000 characters, and bind values (`setLong` above) are never read. It goes
out in the span's data as `db.statement`, with the database name (`"postgresql"`, `"mysql"`,
`"sqlite"`, `"mssql"`, `"oracle"`, or any other; may be null) lowercased as `db.system`. For a query
you timed yourself, use `ForgeOpsTracker.recordDatabaseSpan(name, sql, dbSystem, startedAt,
durationMs)`. `ForgeOpsTracker.databaseSpanData(sql, dbSystem)` builds the data map if you'd rather
add your own keys and call `span`/`recordSpan` with kind `"database"`; a `db.statement` on any
database span is masked the same way, however the map was built.

Masking covers backslash-escaped quotes (`'o\'brien'`), prefixed strings (`E'...'`, `X'DEADBEEF'`,
`N'...'`, `B'...'`, `U&'...'`), and hex, binary, exponent and leading-dot numbers (`0x1F`, `0b101`,
`1.5E-3`, `.5`), never the digits inside a name like `orders2` or a `$1` placeholder. With
`db.system` `"mysql"` or `"mariadb"`, double-quoted text is masked too, since those databases read
`"..."` as a string; for any other database it's a name and is left as is.

### Following a request across services

Traces use the [W3C Trace Context](https://www.w3.org/TR/trace-context/) standard (a `traceparent`
header), so an error or a slow request can be followed from one service into the next.

**Incoming, automatic**: every request that passes through any of the `ForgeOpsTracker*Filter`s
above (or reaches `ForgeOpsTrackerHandlerExceptionResolver`) gets a trace id. A request that
arrives with a valid `traceparent` header continues that trace: same trace id, and its root span
nests under the caller's span. A missing or malformed header just starts a new trace.

**Where an error happened**: every error reported during a request (an unhandled exception, one
Spring's resolver sees, or your own `ForgeOpsTracker.captureException` call) carries:

- `trace_id`: the request's trace id, also returned by `ForgeOpsTracker.currentTraceId()` for your
  own logs.
- `transaction_name`: the same name performance samples and the request's root span use.
- `endpoint`: the HTTP method plus the matched route, e.g. `GET /orders/{id}`: Spring MVC's route
  pattern, or the servlet mapping that matched (e.g. `GET /api/*`) in a plain servlet app. Never
  the raw path, so no ids or tokens; left out when no route has matched (a 404).

These are read when the error is reported, so one reported from inside a Spring handler already has
the matched route. Errors reported outside a request (a background job, a `main()`) are unchanged.
A request that errored always sends its trace, however fast it was, so the issue page can show what
led up to the error. The trace id exists even with `setTrackTracing(false)`, since it is also what
links errors across services; only span reporting stops.

**Outgoing**: this client doesn't instrument an HTTP client itself, so wrap each call you make
during a request in `httpSpan`. It records the call as an `http` span named after the method and
host (never the path or query) and hands your code the headers to add; the header's parent id is
that span's own id, so the called service's spans nest under it:

```java
HttpResponse<String> response = ForgeOpsTracker.httpSpan("POST", paymentsUri, headers -> {
  HttpRequest.Builder request = HttpRequest.newBuilder(paymentsUri).POST(BodyPublishers.ofString(body));
  headers.forEach(request::header);
  return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
});
```

`httpSpan` takes a `java.net.URI` or a `String` (and an optional `Map` of span data), works with any
HTTP client (the headers are a plain `Map<String, String>`), records the span even when the call
throws, and rethrows unchanged. Outside a request the map is empty and nothing is recorded.

The service on the other end must also report to ForgeOps, and both projects must be linked in
ForgeOps to see their errors connected.

Narrow or turn off where the header goes, for example if a third-party API rejects unknown headers:

```java
ForgeOpsTracker.init(config -> {
  config.setPropagateTraces(false); // never hand out traceparent (default true)

  // Default null: every host. A String matches that host and its subdomains on a dot boundary
  // ("example.com" matches "api.example.com", not "badexample.com"); a java.util.regex.Pattern is
  // searched for in the host, so anchor it yourself.
  config.setTracePropagationTargets(List.of("internal.example", Pattern.compile("^10\\.0\\.")));
});
```

## Delivery: a real background thread

The JVM has real native threads, so `DeliveryQueue` runs as a genuine background worker: `push()`
enqueues and returns immediately; one daemon worker thread (started lazily, on first push) blocks
on the queue and delivers over HTTP. Marked a daemon specifically so it can never keep the JVM
alive past a clean shutdown on its own; a shutdown hook sends what's still queued instead, within 5
seconds (see above).

## `in_app` backtrace frames

`StackTraceElement#getFileName()` is always a bare file name (`"MyClass.java"`), never a full
path (verified directly, not assumed) so file-path matching against a configured app root
isn't possible here. Instead, set `Configuration#setAppPackagePrefix("com.acme.myapp")` to your
application's top-level package, and any frame whose declaring class is under that package is
marked `in_app`. Unset by default: every frame reported as not-in-app until you set it, the safe
default.

Backtrace parsing itself needs no regex at all: `Throwable#getStackTrace()` already returns a
structured `StackTraceElement[]`. A related quirk: verified directly that the JVM fills in a
`Throwable`'s stack trace at *construction* time (`fillInStackTrace()` runs inside every
`Throwable` constructor), not at `throw` time, so there's no "empty backtrace" case for an
exception that's constructed but never thrown.

## Source context

By default, each in-app backtrace frame (never a third-party dependency) is captured along with
the 5 lines of source on either side of the culprit line, read straight off disk at capture time,
so an issue's detail page can show the actual code that broke, not just a file:line:method
reference. This never applies to a frame outside your own app's code, and it fails silently (no
context, not an error) for any file that can't be read: most production deployments of a compiled
language don't ship the original `.java` files alongside the compiled `.class`/`.jar`, so this will
typically no-op there and only produce a snippet when source is actually present on disk, such as
during local development.

This is a real, deliberate exception to "off by default is safer": literal source code is being
transmitted, not just a reference to it, and the real protection here is not this flag. Every
project on ForgeOps has its own setting (on by default, off durably and immediately once an org
owner turns it off, regardless of what any individual app's own local setting is still set to)
that governs whether the server will ever actually store what this client sends. Set
`Configuration#setCaptureSourceContext(false)` if you'd rather this client never even attempt the
disk read in the first place:

```java
ForgeOpsTracker.init(config -> {
  config.setDsn("...");
  config.setCaptureSourceContext(false);
});
```

## PII scrubbing

The message, backtrace, any context/tags you attach, and any breadcrumb trail attached are scanned
for likely personal data (email addresses, formatted SSNs/credit cards, known API key/token
formats, and anything under a suspiciously-named key like `password`, `apiKey`, or `ssn`) and
redacted before the payload ever leaves this process. ForgeOps itself scrubs again on arrival
regardless, so this is a second, earlier layer, not the only one. The user attached via
`captureException`'s third argument or `setUser()` above is a deliberate exception: it's never
scrubbed, since redacting it would defeat the whole point of identifying users in the first place.

To disable it:

```java
ForgeOpsTracker.init(config -> {
  config.setDsn("...");
  config.setScrubPii(false);
});
```

## Strict privacy

For apps that can't let personal or health data (PII, PHI) leave their own servers. With strict
privacy on, an event carries only structured facts about the error, never free text that could
hold a name, an email or a diagnosis:

- The message isn't sent. `message_fingerprint` goes instead: a one-way hash of the message (after
  PII scrubbing, when that's on) with its numbers, hex ids and UUIDs taken out, so ForgeOps still
  groups errors without seeing the text.
- Breadcrumbs aren't recorded or sent, and neither are `endpoint`, the SQL statement (even with
  `setCaptureSqlStatement(true)`) or source context.
- The user is sent as its `id` only, never `email` or `username`.
- Context and tags keep only the keys you allow, and only when the value is a string, number or
  boolean.
- Spans are named by method and host only for outbound calls (`GET api.example.com`), a request or
  span named by a raw path keeps only its first segment (`GET /patients/:path`, `/patients/:path`; a
  route template such as `GET /orders/{id}` is kept as it is), and span data keeps only
  `db.statement` (already masked) and `db.system`.

The exception class, stack frames, release, environment, server name, transaction name, trace id
and SQL object names are sent as before. Performance samples, session counts, metrics and change
snapshots are unchanged: they're already counts and timings.

```java
ForgeOpsTracker.init(config -> {
  config.setDsn("...");
  config.setStrictPrivacy(true);
  config.setPrivacyAllowedFields(List.of("error_code", "feature"));
});
```

Or from the environment: `FORGE_OPS_STRICT_PRIVACY=true` and
`FORGE_OPS_PRIVACY_ALLOWED_FIELDS=error_code,feature` (comma separated). An option set in code wins
over the environment. Both are off and empty by default.

ForgeOps applies the same rules again on arrival when the project's own Strict privacy setting is
on, so an app with an older or misconfigured client still can't get text stored for that project.

## Session tracking (release health)

By default, every request through the servlet integration is counted as a session: crash-free
unless an unhandled exception (or a 5xx response) actually affects it, giving ForgeOps a
crash-free rate per release to show alongside the errors themselves, not just the errors on their
own. Counted in-process and flushed as a small periodic aggregate on a
`ScheduledExecutorService` (never one network call per request), the same delivery philosophy as
everything else in this client: a broken or unreachable tracker never affects the host app either
way. A JVM shutdown hook flushes whatever's left on a clean exit.

```java
import java.time.Duration;

ForgeOpsTracker.init(config -> {
  config.setDsn("...");
  config.setTrackSessions(false); // opt out entirely
  config.setSessionFlushInterval(Duration.ofSeconds(30)); // default 60 seconds
});
```

Requires a ForgeOps plan that includes release health; on a plan that doesn't, the periodic
flushes are accepted but not recorded, and the response says why.

## Performance monitoring

By default, the servlet integration also times every request, so a dashboard widget on ForgeOps
can show which parts of your app are actually slow, not just which ones raise. Bucketed by
transaction (`"GET /users/{id}"`, Spring MVC's own matched handler pattern when running under
Spring, not the raw URI, so a distinct user id doesn't explode into its own separate transaction;
falls back to the raw URI for a plain servlet app or a request no handler ever matched) and
flushed as a small periodic aggregate per transaction on the same kind of scheduled executor
session tracking above uses.

Each aggregate also carries a small latency histogram (a count per fixed latency bucket: 50, 100,
250, 500, 1000, 2500, 5000 and 10000ms, plus an overflow bucket), so ForgeOps can show an
approximate p50/p95/p99 per transaction, not just an average. Percentiles are accurate to the width
of whichever bucket a duration falls into; the SDK never stores the individual durations.

```java
ForgeOpsTracker.init(config -> {
  config.setDsn("...");
  config.setTrackPerformance(false); // opt out entirely
  config.setPerformanceFlushInterval(Duration.ofSeconds(30)); // default 60 seconds
});
```

Requires a ForgeOps plan that includes performance monitoring; on a plan that doesn't, the
periodic flushes are accepted but not recorded, and the response says why.

## Custom metrics and infrastructure monitoring

Two explicit calls (nothing is automatic, so there is no `trackMetrics` flag): a business event you
name yourself, and a reading from one of your own hosts.

```java
ForgeOpsTracker.captureMetric("signup"); // value defaults to 1: a bare counter
ForgeOpsTracker.captureMetric("payment", 49.0); // a real magnitude; it may be negative (a refund)

ForgeOpsTracker.captureInfrastructureMetric("cpu", 0.42); // hostname defaults to serverName
ForgeOpsTracker.captureInfrastructureMetric("disk", 0.81, "db-1");
ForgeOpsTracker.flushMetrics(); // optional: send right now
```

Each capture is buffered and flushed as one batch every `metricFlushInterval` /
`infrastructureMetricFlushInterval` (60 seconds by default) on a daemon scheduled executor, and once
more from a JVM shutdown hook when the program exits normally, so a short-lived cron job that captures
a few readings and returns from `main` needs nothing more; call `flushMetrics()` if it ends with
`Runtime.halt`. Every entry is stored as it was captured (a signup is a row, not a running total), so a
count or sum you compute later is exact. Both are a no-op when the client isn't enabled for the
environment.

A delivery that fails for now (a network error, a timeout, a 5xx, a 408 or a 429) keeps every entry
for the next flush, and an entry captured while a delivery is in flight is kept too (the Ruby gem's
own buffer loses it; a test pins this with a gated delivery). A batch the server rejects (any other
4xx, such as the 403 of a plan without the feature) is dropped and never sent again, and so is a batch
whose delivery has failed five flushes in a row. The buffer holds at most 1000 entries per kind and
drops further ones until a flush empties it.
A NaN or infinite value is dropped at capture: it is not valid JSON and would make the server reject
the whole batch behind it. Requires a ForgeOps plan that includes custom metrics / infrastructure
monitoring.

## What changed

Record a change your app makes to itself, so ForgeOps can line it up against the errors and slowdowns
that follow:

```java
import io.github.lukepopwell.tracker.Change;
import io.github.lukepopwell.tracker.ForgeOpsTracker;
import java.util.Map;

ForgeOpsTracker.recordChange("config", "Raised the connection pool to 40");

ForgeOpsTracker.recordChange(Change.of("feature_flag", "Enabled new checkout for 25% of users")
    .details(Map.of("flag", "new_checkout", "rollout", 25))
    .actor("luke@example.com")
    .url("https://github.com/acme/shop/pull/812")
    .id("new-checkout-25")); // optional idempotency key
```

`kind` is one of `feature_flag`, `config`, `migration`, `dependency`, `infrastructure` or `other`;
anything else is sent as `other`. `title` is required (at most 200 characters). `environment`
defaults to the configured environment and `occurred_at` to now; `service`, `actor`, `url`, `id` and
`occurredAt` are optional setters on `Change`. Delivery happens on the same background thread as
errors, so the call returns immediately; it is a no-op when the client isn't enabled and never throws
(on a plan without change tracking the server rejects it and it is dropped).

`init()` also sends one snapshot per process of what this JVM sees (the Java runtime version, e.g.
`java 21.0.2`), from a background thread so it never delays startup. ForgeOps compares it with the
previous snapshot for the same environment and records what changed. Library versions aren't
included: a Java classpath has no reliable record of them, so this client leaves them out rather than
guess.

```java
ForgeOpsTracker.init(config -> {
  config.setDetectChanges(false);   // no startup snapshot at all (default true)
  config.setTrackEnvVarNames(true); // also send environment variable NAMES, never values (default false)
});
```

With `setTrackEnvVarNames(true)`, names that differ from host to host are left out so a steady fleet
doesn't look like it's changing: `HOSTNAME`, `HOST`, `HOME`, `PATH`, `PWD`, `OLDPWD`, `SHLVL`, `_`,
`TERM`, `USER`, `LOGNAME`, `SHELL`, `LANG`, `LC_*`, `TMPDIR`, `TZ`, `PORT`, `DYNO`, `INVOCATION_ID`,
`JOURNAL_STREAM`, `SYSTEMD_*`, `MEMORY_PRESSURE_*`, `KUBERNETES_*`, `*_SERVICE_HOST`,
`*_SERVICE_PORT*`, `*_PORT_*_TCP*`, and this client's own `FORGE_OPS_*`.

## JSON encoding

The JDK has no JSON support in its standard library. Rather than pull in a dependency for it, this
client ships a small internal `Json` class that encodes exactly the shapes this client's own
payloads use (nested `Map`/`List` of `String`/`Number`/`Boolean`/`null`), written by hand since the
JDK doesn't offer one. It only ever encodes, never parses: this client never reads JSON back.

## Database errors

When an error comes from a database call, the event includes the names of the stored procedure, table and view its SQL touched, so the issue tells you where to start looking. This is on by default and sends identifiers only, never values. The statement is read from a `getSQL()` or `getSql()` accessor on the exception or anything it wraps (Hibernate's `JDBCException`, Spring's `BadSqlGrammarException` and `UncategorizedSQLException`), reflectively, so this client depends on none of them. Plain JDBC exceptions carry no statement.

To also send the SQL statement itself, opt in. Every string and number is replaced by `?` before it
leaves your process (`WHERE email = 'a@b.co' AND id = 42` is sent as `WHERE email = ? AND id = ?`),
and ForgeOps masks it again on arrival. Escaped quotes, prefixed strings such as `X'DEADBEEF'`, and
hex, binary and exponent numbers are masked the same way:

```java
ForgeOpsTracker.init(config -> {
  config.setCaptureSqlStatement(true); // default false
  // config.setCaptureSqlObjects(false); // default true; false stops even the names
});
```

Each ForgeOps project also has its own "Capture the SQL behind database errors" setting. Turn it off
there and the statement is never stored for that project, whatever this flag says; the names are
still kept. A view and a table are written the same way in SQL, so both show as tables/views; the
database's own error message usually settles which it was.

## Running the tests

```bash
cd sdks/java
mvn test              # JUnit 5: 253 examples
mvn checkstyle:check   # style (checkstyle.xml, trimmed down from google_checks.xml)
```
