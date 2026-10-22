# Changelog

## 0.17.0 (2026-10-22)

- Strict privacy: the message fingerprint now also replaces email addresses with `<email>` and quoted
  values (`"..."`, and `'...'` when the quotes aren't part of a word) with `<str>`, before the existing
  steps. `KeyError: 'Jane Doe'` and `KeyError: 'Bob Smith'` now group together, and no guessable value sits
  behind the hash. Errors whose messages contain quotes or emails get new fingerprints once, so they open
  new issues after upgrading.

## 0.16.1 (2026-10-22)

- The README says what strict privacy does in its opening lines.
- The source is public at https://github.com/Luke-Popwell/forge-ops-tracker-java, and the package links to it.

## 0.16.0 (2026-10-20)

- Strict privacy: a raw path now keeps only its first segment, instead of only losing its digit segments, since a later segment can name a person even without a digit (`/patients/janedoe`). `GET /patients/jane-doe/12` is sent as `GET /patients/:path`, `/42/edit` as `/:id/:path`. A route template (any segment such as `:id`, `{id}`, `<int:id>`, `[slug]` or `*path`) is kept as it is, so `GET /v1/orders/{id}` is no longer sent as `GET /:id/orders/{id}`. Applies to span names and transaction names (events and performance samples), the same rule the server applies. New `StrictPrivacy.strictPath`; `pathWithoutIds` still works and now calls it.

## 0.15.0 (2026-10-20)

- Strict privacy, for apps that can't let personal or health data leave their own servers. New `Configuration.setStrictPrivacy(true)` (or `FORGE_OPS_STRICT_PRIVACY=true`) sends only structured facts: the message is replaced by `message_fingerprint`, a hash of it (after PII scrubbing) with numbers and ids taken out, so errors still group; breadcrumbs aren't recorded or sent; `endpoint`, the SQL statement and source context aren't sent; the user is sent as its `id` only; and context and tags keep only the keys in `setPrivacyAllowedFields(...)` (or `FORGE_OPS_PRIVACY_ALLOWED_FIELDS=error_code,feature`) with a string, number or boolean value. Spans keep only method and host for outbound calls, a request or span named by a raw path has its ids taken out (`GET /patients/:id`, `/patients/:id`), and span data keeps only `db.statement` and `db.system`. Off by default; nothing changes unless it's turned on. ForgeOps applies the same rules on arrival when the project's own Strict privacy setting is on.

## 0.14.0 (2026-10-13)

- A batch the server will never accept is no longer re-sent forever. Performance samples, custom and infrastructure metrics and session checkins used to keep a batch after any failed delivery and send it again, with more added, at every flush. A 4xx answer other than 408 and 429 (a wrong key, a plan without the feature, a batch the server can't read or that is too large) now drops the batch at once, since the same batch would only get the same answer. Before this, a rejected metric batch also stayed in the 1000-entry buffer, so once the buffer filled, every metric captured afterward was dropped too.
- A delivery that fails for now (a network error, a timeout, a 5xx, a 408 or a 429) still keeps the batch for the next flush, but only so long: after 5 failed flushes in a row the batch is dropped and the next one starts clean. A successful flush starts the count over. The 1000-entry cap on each metric buffer is unchanged.
- Each drop is reported once through `setLogger`, for example `[ForgeOpsTracker] dropped 12 metrics: the server rejected it` or `... delivery failed 5 times in a row`, and a non-2xx answer is logged with its status (`delivery rejected: HTTP 403`, `delivery failed: HTTP 503`). Nothing about delivery throws.
- No API changes: `Client`'s `deliver...` methods still return `true` only for a 2xx, and errors, traces and changes are still sent once and never retried.

## 0.13.0 (2026-10-02)

- **The default environment changed from `development` to `production`.** With `FORGE_OPS_ENVIRONMENT` unset and no `setEnvironment` call, the client used to report itself as `development`, which isn't one of the default enabled environments (`production`, `staging`), so an app set up with only a DSN silently sent nothing. It now defaults to `production`, so a DSN alone is enough. To keep the old behavior, set `FORGE_OPS_ENVIRONMENT=development` (or call `config.setEnvironment("development")`) wherever you don't want errors sent, such as developer machines and CI. `SPRING_PROFILES_ACTIVE` is not read: it lists profiles, not an environment name.
- When a DSN is configured but the environment isn't enabled, `init()` now says so once per process, through `setLogger` if one is set and on `stderr` otherwise: `[ForgeOps] Not sending: this environment is "development", and only production, staging are enabled. Set FORGE_OPS_ENVIRONMENT=production (or add "development" to the enabled environments) to send from here.` Nothing is printed when no DSN is configured. New `Configuration.notSendingWarning()` returns that message, or null.
- The README's catch-site and `flush()` examples now include their imports.

## 0.12.1 (2026-10-01)

- Errors reported just before a program exits are no longer lost. A command-line tool or cron job that reported an error and then returned from `main` or called `System.exit` could end before the background thread sent it, and so could a crash reported by the uncaught-exception handler. The first report now registers a JVM shutdown hook that sends whatever is still queued before the JVM exits, waiting at most 5 seconds; a program that never reports anything doesn't wait at all. New `ForgeOpsTracker.flush()` (and `flush(Duration)`) sends queued errors right away, for a program that ends with `Runtime.halt`, which skips shutdown hooks.

## 0.12.0 (2026-09-29)

- SQL masking now matches the server's newer rules, so fewer values can slip through. A backslash-escaped quote no longer ends a string early (`'o\'brien'` and `E'o\'brien'` are each one `?`), a string's type prefix goes with it (`X'DEADBEEF'`, `N'...'`, `B'...'`, `U&'...'`), and hex, binary, exponent and leading-dot numbers are masked (`0x1F`, `0b101`, `3e10`, `1.5E-3`, `.5`). A database span whose `db.system` is `mysql` or `mariadb` also has its double-quoted strings masked, since those databases read `"..."` as a string, not a name. A string cut off right after a backslash is masked to the end too. Applies to database spans and to the statement sent with a database error.

## 0.11.0 (2026-09-25)

- A database span can now carry the SQL it ran. New `ForgeOpsTracker.databaseSpan(name, sql, dbSystem, work)` and `ForgeOpsTracker.recordDatabaseSpan(name, sql, dbSystem, startedAt, durationMs)` record a `database` span whose data holds the statement as `db.statement` and the database name, lowercased, as `db.system` (`databaseSpanData(sql, dbSystem)` builds that map for `span`/`recordSpan`). The statement is masked (every string and number becomes `?`) and cut to 4000 characters when the span is recorded, and a `db.statement` on any database span is masked the same way, so the SQL as written never leaves the process. Bind values are never read.

## 0.10.0 (2026-09-25)

- `ForgeOpsTracker.recordChange(kind, title)` (plus a `details` overload, and `recordChange(Change)` for the optional `environment`, `service`, `actor`, `url`, `id` and `occurredAt`) records an explicit change, such as a feature flag flip, a config edit or a migration, so ForgeOps can line it up against errors. `kind` is one of `feature_flag`, `config`, `migration`, `dependency`, `infrastructure` or `other`; anything else is sent as `other`. Delivered on the existing background delivery thread, a no-op when the client isn't enabled, and never throws.
- `init()` sends one change snapshot per process (the Java runtime version) from a background thread, so ForgeOps can show what changed between deploys. New `setDetectChanges` (default true) turns it off. New opt-in `setTrackEnvVarNames` (default false) also sends environment variable names, never values, minus host-specific ones such as `HOSTNAME`, `PATH` and `KUBERNETES_*`. Library versions are not sent: a Java classpath has no reliable record of them.

## 0.9.0

- Distributed tracing across services, using the W3C Trace Context standard (`traceparent`). Every request that passes through any of the servlet filters (or reaches `ForgeOpsTrackerHandlerExceptionResolver`) now gets a trace id; one that arrives with a valid `traceparent` header continues that trace, and its root span points at the caller's span. A missing or malformed header starts a fresh trace. `ForgeOpsTracker.currentTraceId()` returns it.
- Every error reported during a request now says where it happened: `trace_id`, `transaction_name` (the same name performance samples and traces already use), and `endpoint` (the HTTP method plus Spring MVC's matched route pattern, e.g. `GET /orders/{id}`, or the matched servlet mapping in a plain servlet app; never the raw path). Read when the error is reported, so an error reported from inside a handler already has them. Left out outside a request. Structured fields, so they're never PII-scrubbed.
- `ForgeOpsTracker.httpSpan(method, url, headers -> ...)` records an outgoing HTTP call as an `http` span named after the method and host, and hands your code the `traceparent` header to add to it; its parent id is the span's own id, so the called service's root span nests under it. This client doesn't instrument an HTTP client itself. New `setPropagateTraces` (default true) and `setTracePropagationTargets` (default null, meaning every host; or a list of host Strings, each matching that host and its subdomains, and/or `Pattern`s) control where the header goes.
- An errored request's trace is always sent, however fast it was: an exception escaping the filter chain, or any error reported with `captureException` while the request ran. Fast, successful requests are unchanged.
- With `setTrackTracing(false)`, requests still get a trace id (attached to errors and handed out by `httpSpan`, since that is also what links errors across services); only span reporting stops.
- Trace and span ids were already W3C shaped (32 and 16 lowercase hex characters); they are now also guaranteed never to be all zeros, the one value the standard reserves as invalid.
- `ForgeOpsTrackerPerformanceTrackingFilter` no longer needs `spring-webmvc` on the runtime classpath: it read Spring's route pattern attribute name off a constant that Spring computes at class load time, so a plain servlet app without Spring could fail with `NoClassDefFoundError` when a request finished.

## 0.8.0

- Database errors now say where to look. When an error carries the SQL behind a failed database call (`getSQL()`/`getSql()` on Hibernate's `JDBCException` or Spring's `BadSqlGrammarException`, or anything they wrap), the event carries the names of the stored procedure, table and view that SQL touched. On by default (`setCaptureSqlObjects`); names are identifiers, never values. New opt-in `setCaptureSqlStatement` (default false) also sends the statement itself, with every string and number replaced by `?`. Each project has its own server-side setting that can stop the statement being stored regardless of this flag; the names are still kept.

This client has real prior tagged releases (see `pom.xml`'s own version history in source control),
but no `CHANGELOG.md` ever existed for it before this entry: unlike `sdks/php`'s own first entry
(a genuine "no prior release exists at all" situation), this one starts the file itself without
backfilling every earlier version, since reconstructing that history isn't part of this change.

## 0.7.1

- Documentation only: the README and package description now describe ForgeOps as a hosted service, link to getforgeops.net, and show the real host in the connection string example instead of a placeholder. No code changes.

## 0.7.0

- Performance percentiles: every performance sample now carries a small latency histogram alongside
  its count/sum/max (fixed buckets of 50, 100, 250, 500, 1000, 2500, 5000 and 10000ms, plus an
  overflow bucket), so ForgeOps can show an approximate p50/p95/p99 per transaction instead of
  only an average. No new config; this rides the existing performance tracking flag and flush
  interval.

## 0.6.0

- Breadcrumbs: a trail of what happened right before an error, recorded automatically once
  `ForgeOpsTrackerBreadcrumbContextFilter` is registered (on by default once registered, same as
  every other automatic instrumentation this client does; opt out with `trackBreadcrumbs`, or tune
  `maxBreadcrumbs`, default 30). One automatic "controller" breadcrumb per request, from
  `ForgeOpsTrackerPerformanceTrackingFilter`: the only automatic source this client has, since it
  has no query-level/ORM instrumentation for a second one to hang off of, unlike the Ruby gem's own
  `sql.active_record` subscription. `ForgeOpsTracker.addBreadcrumb(message, category, level, data)`
  (with cascading overloads for fewer arguments, mirroring `captureException`'s own convention)
  adds your own, regardless of whether the automatic source is on, and works outside a request
  entirely too (a background worker, a plain `main()`). A `ThreadLocal<BreadcrumbBuffer>` on
  `ForgeOpsTracker`, the same isolation boundary `currentUser` already uses for the same reason
  (a servlet container's one-thread-per-request model). Spring MVC needs no separate
  breadcrumb-scoping interceptor: confirmed directly that a `HandlerInterceptor`/
  `HandlerExceptionResolver` always runs from inside `DispatcherServlet`'s own dispatch, itself
  always invoked from inside `ForgeOpsTrackerBreadcrumbContextFilter`'s wrapped
  `chain.doFilter`, on the same thread, so the servlet-level filter alone already covers it.
  Unlike the affected user, a breadcrumb's message/data is scrubbed for likely PII. 64 new tests
  written and run (`mvn test`; 108 total in the suite, up from 44).
