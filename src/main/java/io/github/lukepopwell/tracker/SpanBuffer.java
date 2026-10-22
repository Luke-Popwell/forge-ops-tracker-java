package io.github.lukepopwell.tracker;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One request's (or one background job's) worth of spans, sharing a single trace id. Held
 * one-per-thread by {@code ForgeOpsTracker}, the same isolation boundary as {@link
 * BreadcrumbBuffer}, so no synchronization is needed: only the thread that owns it ever touches it.
 * Nesting comes from a stack of open span ids: a span started while another is open becomes its
 * child.
 *
 * <p>The trace id and the root span's remote parent come from the request's {@link RequestState}
 * when there is one: the caller's own ids when the request arrived with a usable {@code traceparent}
 * header (see {@link TraceParent}), so the root span nests under the caller's outgoing span even
 * though the server receives the two in different uploads.
 */
public class SpanBuffer {
  static final int MAX_SPANS = 500;
  static final String DB_STATEMENT = "db.statement";
  static final String DB_SYSTEM = "db.system";
  /** The kinds the ingestion API accepts; anything else would fail validation for the whole trace. */
  private static final java.util.Set<String> KINDS =
      java.util.Set.of("controller", "service", "database", "redis", "http", "job", "other");
  private static final DateTimeFormatter TIMESTAMP_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private final Configuration configuration;
  private final String traceId;
  private final String remoteParentSpanId;
  private final String rootSpanId = TraceParent.generateSpanId();
  private final List<Map<String, Object>> spans = new ArrayList<>();
  private final Deque<String> openSpanIds = new ArrayDeque<>();

  public SpanBuffer(Configuration configuration) {
    this(configuration, TraceParent.generateTraceId(), null);
  }

  /**
   * @param remoteParentSpanId the calling service's span this trace's root runs under, or {@code
   *     null} when this trace started here.
   */
  public SpanBuffer(Configuration configuration, String traceId, String remoteParentSpanId) {
    this.configuration = configuration;
    this.traceId = traceId;
    this.remoteParentSpanId = remoteParentSpanId;
  }

  public String traceId() {
    return traceId;
  }

  /** Opens a span, returning its id; pair with {@link #finish}. */
  String open() {
    return open(TraceParent.generateSpanId());
  }

  /**
   * Opens a span under an id generated beforehand, as {@link ForgeOpsTracker#httpSpan} does so its
   * header can name it.
   */
  String open(String id) {
    openSpanIds.push(id);
    return id;
  }

  /** Closes a span opened with {@link #open}, recording it. */
  void finish(String id, String name, String kind, Instant startedAt, double durationMs, Map<String, Object> data) {
    openSpanIds.remove(id);
    record(id, currentParentId(), name, kind, startedAt, durationMs, data);
  }

  /** Records a span that is already finished, as a child of whatever is currently open. */
  public void recordLeaf(String name, String kind, Instant startedAt, double durationMs, Map<String, Object> data) {
    record(TraceParent.generateSpanId(), currentParentId(), name, kind, startedAt, durationMs, data);
  }

  private String currentParentId() {
    String top = openSpanIds.peek();
    return top != null ? top : rootSpanId;
  }

  private void record(String id, String parentId, String name, String kind, Instant startedAt, double durationMs,
      Map<String, Object> data) {
    if (spans.size() >= MAX_SPANS - 1) {
      return; // leave room for the root
    }
    spans.add(build(id, parentId, name, kind, startedAt, durationMs, data));
  }

  private Map<String, Object> build(String id, String parentId, String name, String kind, Instant startedAt,
      double durationMs, Map<String, Object> data) {
    Map<String, Object> span = new LinkedHashMap<>();
    boolean strict = configuration.isStrictPrivacy();
    span.put("span_id", id);
    span.put("parent_span_id", parentId);
    // Strict privacy: an outbound call is named by its method and host only, never a path that
    // could name a person, and data keeps only the masked statement and the database system.
    span.put("name", strict ? StrictPrivacy.spanName(name) : name);
    span.put("kind", kind != null && KINDS.contains(kind) ? kind : "other");
    span.put("started_at", TIMESTAMP_FORMAT.format(startedAt));
    span.put("duration_ms", Math.round(durationMs * 100.0) / 100.0);
    span.put("environment", configuration.getEnvironment());
    span.put("release", configuration.getRelease());
    Map<String, Object> sent = data == null ? Map.of() : "database".equals(kind) ? maskDatabaseData(data) : data;
    span.put("data", strict ? StrictPrivacy.spanData(sent) : sent);
    return span;
  }

  /**
   * A copy of a database span's data with {@code db.statement} masked by {@link SqlStatement} (for
   * the span's {@code db.system}, if any), so the SQL as written never reaches the payload however
   * the map was built. A statement that isn't
   * a String, or is blank, is dropped. The caller's map is never modified.
   */
  static Map<String, Object> maskDatabaseData(Map<String, Object> data) {
    if (!data.containsKey(DB_STATEMENT)) {
      return data;
    }
    Map<String, Object> masked = new LinkedHashMap<>(data);
    masked.remove(DB_STATEMENT);
    if (data.get(DB_STATEMENT) instanceof String statement) {
      Object system = data.get(DB_SYSTEM);
      String value = SqlStatement.mask(statement, system instanceof String ? (String) system : null);
      if (value != null) {
        masked.put(DB_STATEMENT, value);
      }
    }
    return masked;
  }

  /**
   * Builds the wire payload once the request is over: the root span plus everything recorded
   * beneath it, or {@code null} when the root was faster than {@code traceCaptureThreshold}.
   */
  Map<String, Object> finishTrace(String rootName, Instant startedAt, double durationMs) {
    return finishTrace(rootName, startedAt, durationMs, false);
  }

  /**
   * Same, but {@code always} sends it however fast it was: an errored request's trace is what an
   * issue page wants to show next to the error, slow or not.
   */
  Map<String, Object> finishTrace(String rootName, Instant startedAt, double durationMs, boolean always) {
    if (!always && durationMs < configuration.getTraceCaptureThreshold().toNanos() / 1_000_000.0) {
      return null;
    }
    List<Map<String, Object>> all = new ArrayList<>(spans);
    all.add(0, build(rootSpanId, remoteParentSpanId, rootName, "controller", startedAt, durationMs, Map.of()));
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("trace_id", traceId);
    payload.put("spans", all);
    return payload;
  }
}
