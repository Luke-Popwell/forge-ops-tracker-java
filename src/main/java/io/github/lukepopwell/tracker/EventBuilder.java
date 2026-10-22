package io.github.lukepopwell.tracker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a caught {@link Throwable} into the JSON-able payload shape the ingestion API expects.
 *
 * <p>Unlike the Ruby/PHP/Node clients, backtrace parsing here is not a regex over a string: {@link
 * Throwable#getStackTrace()} already returns a structured {@link StackTraceElement}[], the same
 * situation as Python's {@code traceback} module. Also verified directly (not assumed): the JVM
 * fills in a {@code Throwable}'s stack trace at construction time ({@code fillInStackTrace()} runs
 * inside every {@code Throwable} constructor), not at {@code throw} time: the same as PHP and
 * Node, unlike Ruby/.NET/Python: so there's no "empty backtrace when never thrown" case here
 * either.
 */
public class EventBuilder {
  private static final int MAX_FRAMES = 500;

  /**
   * How many lines of source to grab on either side of the culprit line (see {@link
   * #attachSourceContext}), and the longest a single captured line is allowed to be before
   * getting truncated: guards against a single pathological minified/generated line ballooning
   * the payload. ForgeOps itself re-truncates on arrival too, the same "don't just trust the SDK"
   * posture {@link #MAX_FRAMES} already gets on the server side.
   */
  private static final int CONTEXT_LINES = 5;

  private static final int MAX_CONTEXT_LINE_LENGTH = 500;

  /**
   * Identifies this client to the server's auto language-detection on the project the event
   * lands in (see {@code Project#note_sdk_platform} server-side); matches this repo's own {@code
   * sdks/java} directory name, the same convention every other language's client follows.
   */
  private static final String SDK_NAME = "java";

  private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_INSTANT;

  private final Configuration configuration;

  public EventBuilder(Configuration configuration) {
    this.configuration = configuration;
  }

  public Map<String, Object> build(Throwable error, Map<String, Object> context) {
    Map<String, Object> payload = new LinkedHashMap<>();
    boolean strict = configuration.isStrictPrivacy();
    payload.put("exception_class", error.getClass().getName());
    if (strict) {
      // Strict privacy: the text never leaves this process, only a hash of it for grouping, taken
      // from the message as it would otherwise have been sent (scrubbed, when scrubbing is on) so
      // it matches what the server fingerprints from a client without strict privacy.
      String message = error.getMessage();
      if (message != null && configuration.isScrubPii()) {
        message = (String) PiiScrubber.scrub(message);
      }
      payload.put("message", "");
      payload.put("message_fingerprint", StrictPrivacy.messageFingerprint(message));
    } else {
      payload.put("message", String.valueOf(error.getMessage()));
    }
    payload.put("backtrace", backtraceFrames(error));
    payload.put("occurred_at", TIMESTAMP_FORMAT.format(Instant.now()));
    payload.put("environment", configuration.getEnvironment());
    payload.put("release", configuration.getRelease());
    payload.put("server_name", configuration.getServerName());
    if (strict) {
      putIfNotNull(payload, "context", StrictPrivacy.allowed(context, configuration.getPrivacyAllowedFields()));
      putIfNotNull(payload, "tags", StrictPrivacy.allowed(Map.of(), configuration.getPrivacyAllowedFields()));
    } else {
      payload.put("context", context == null ? Map.of() : context);
      payload.put("tags", Map.of());
    }
    payload.put("sdk_name", SDK_NAME);
    attachSql(payload, error);
    return scrub(payload);
  }

  // See SqlStatement for what's read off the error and how it's masked. The statement itself only
  // goes out when captureSqlStatement is on (and strict privacy is off); the extracted names go out on their own
  // (captureSqlObjects) so an issue can still name the procedure or view involved.
  private void attachSql(Map<String, Object> payload, Throwable error) {
    if (!configuration.isCaptureSqlObjects() && !configuration.isCaptureSqlStatement()) {
      return;
    }

    String masked = SqlStatement.mask(SqlStatement.findIn(error));
    if (masked == null) {
      return;
    }

    Map<String, Object> objects = SqlStatement.objects(masked);
    if (objects != null && configuration.isCaptureSqlObjects()) {
      payload.put("sql_objects", objects);
    }
    if (configuration.isCaptureSqlStatement() && !configuration.isStrictPrivacy()) {
      payload.put("sql_statement", masked);
    }
  }

  /**
   * Calls the two-argument {@link #build} above (virtually dispatched, so a subclass overriding
   * just that one, the way {@code ReporterTest}'s own {@code ExplodingEventBuilder} does, still
   * gets honored here too) and, when given one, attaches {@code user} to the result afterward,
   * never scrubbed unlike every other free-text field {@link #scrub} already covers: the whole
   * point of this field is that it's deliberately identifiable, not something to redact.
   *
   * <p>Under strict privacy only the user's {@code id} is sent, as a string.
   *
   * @param user the affected user, if any; omitted from the payload entirely when null or empty.
   */
  public Map<String, Object> build(Throwable error, Map<String, Object> context, Map<String, Object> user) {
    Map<String, Object> payload = build(error, context);
    Map<String, Object> sent = configuration.isStrictPrivacy() ? StrictPrivacy.user(user) : user;
    if (sent != null && !sent.isEmpty()) {
      payload.put("user", sent);
    }
    return payload;
  }

  /**
   * Calls the three-argument {@link #build} above and, when given a non-empty trail, attaches
   * {@code breadcrumbs} to the result afterward. Unlike {@code user}, a breadcrumb's
   * message/data is exactly the kind of free text (a bind parameter, a URL with a token in it)
   * {@link #scrub} exists to catch, so this scrubs it explicitly here rather than exempting it
   * the way {@code user} is: the same distinction the Ruby gem's own {@code EventBuilder#scrub}
   * doc comment draws between the two fields.
   *
   * @param breadcrumbs the current thread's trail, oldest first; omitted from the payload
   *     entirely when null or empty, and always under strict privacy.
   */
  public Map<String, Object> build(
      Throwable error, Map<String, Object> context, Map<String, Object> user, List<Map<String, Object>> breadcrumbs) {
    Map<String, Object> payload = build(error, context, user);
    if (breadcrumbs != null && !breadcrumbs.isEmpty() && !configuration.isStrictPrivacy()) {
      payload.put("breadcrumbs", configuration.isScrubPii() ? PiiScrubber.scrub(breadcrumbs) : breadcrumbs);
    }
    return payload;
  }

  /**
   * Calls the four-argument {@link #build} above and attaches where the error happened: {@code
   * trace_id} (the request's or open trace's 32-character lowercase hex W3C trace id, which is also
   * what links this error to errors other services reported for the same trace), {@code
   * transaction_name} (the same name the request's root span and performance samples use), and
   * {@code endpoint} (the HTTP method plus the matched route pattern, never the raw path). Each is
   * left out entirely when null, as all three are outside a request. Structured fields, never
   * scrubbed, the same exemption {@code user} gets. Strict privacy leaves {@code endpoint} out, and
   * gives {@code transaction_name} the span name rule (see {@link StrictPrivacy#spanName}).
   */
  public Map<String, Object> build(Throwable error, Map<String, Object> context, Map<String, Object> user,
      List<Map<String, Object>> breadcrumbs, String traceId, String transactionName, String endpoint) {
    Map<String, Object> payload = build(error, context, user, breadcrumbs);
    if (transactionName != null) {
      // Under strict privacy a raw path (the fallback when no route matched) loses its ids.
      payload.put("transaction_name",
          configuration.isStrictPrivacy() ? StrictPrivacy.spanName(transactionName) : transactionName);
    }
    if (endpoint != null && !configuration.isStrictPrivacy()) {
      payload.put("endpoint", endpoint);
    }
    if (traceId != null) {
      payload.put("trace_id", traceId);
    }
    return payload;
  }

  // exception_class/occurred_at/environment/release/server_name/sdk_name are left alone:
  // structured fields this client or the host app sets deliberately, not free text an exception
  // or its context could accidentally spill sensitive data into. user (see the three-argument
  // build() above) never even reaches this method at all, the same exemption for the same reason,
  // and neither do trace_id/transaction_name/endpoint (see the seven-argument build() above).
  // breadcrumbs is scrubbed separately, up in the four-argument build() above, not here: it never
  // reaches this method either, but unlike user it is not exempt from scrubbing at all.
  private Map<String, Object> scrub(Map<String, Object> payload) {
    if (!configuration.isScrubPii()) {
      return payload;
    }
    Map<String, Object> scrubbed = new LinkedHashMap<>(payload);
    scrubbed.put("message", PiiScrubber.scrub(payload.get("message")));
    scrubbed.put("backtrace", PiiScrubber.scrub(payload.get("backtrace")));
    // Under strict privacy context and tags are left out when nothing allowed is left in them.
    if (payload.containsKey("context")) {
      scrubbed.put("context", PiiScrubber.scrub(payload.get("context")));
    }
    if (payload.containsKey("tags")) {
      scrubbed.put("tags", PiiScrubber.scrub(payload.get("tags")));
    }
    if (payload.containsKey("sql_statement")) {
      scrubbed.put("sql_statement", PiiScrubber.scrub(payload.get("sql_statement")));
    }
    return scrubbed;
  }

  private static void putIfNotNull(Map<String, Object> payload, String key, Object value) {
    if (value != null) {
      payload.put(key, value);
    }
  }

  private List<Object> backtraceFrames(Throwable error) {
    StackTraceElement[] elements = error.getStackTrace();
    List<Object> frames = new ArrayList<>();
    int limit = Math.min(elements.length, MAX_FRAMES);
    for (int i = 0; i < limit; i++) {
      frames.add(frame(elements[i]));
    }
    return frames;
  }

  private Map<String, Object> frame(StackTraceElement element) {
    Map<String, Object> frame = new LinkedHashMap<>();
    String file = element.getFileName();
    boolean inApp = isInApp(element);
    frame.put("file", file);
    frame.put("line", element.getLineNumber());
    frame.put("method", element.getClassName() + "#" + element.getMethodName());
    frame.put("in_app", inApp);
    if (configuration.isCaptureSourceContext() && !configuration.isStrictPrivacy() && inApp) {
      attachSourceContext(frame, file, element.getLineNumber());
    }
    return frame;
  }

  /**
   * Reads a few lines of source straight off disk around the culprit line, at exactly the path
   * recorded on the frame, and adds {@code context_line}/{@code pre_context}/{@code post_context}
   * to it. Only ever called for an in-app frame with {@link Configuration#isCaptureSourceContext}
   * on (see {@link #frame}): never a third-party dependency, and never at all when the flag is
   * off. Best-effort: any failure (the file doesn't exist, isn't readable, or the path doesn't
   * resolve: the expected case for most production deployments of a compiled language, which
   * ship only the compiled {@code .class}/{@code .jar}, not the original {@code .java} files)
   * just leaves the frame without context, never a reason to fail building the whole event.
   */
  private void attachSourceContext(Map<String, Object> frame, String file, int line) {
    if (file == null) {
      return;
    }
    try {
      List<String> lines = readSourceLines(file);
      int index = line - 1;
      if (index < 0 || index >= lines.size()) {
        return;
      }
      int from = Math.max(index - CONTEXT_LINES, 0);
      int to = Math.min(index + CONTEXT_LINES, lines.size() - 1);
      frame.put("context_line", truncateLine(lines.get(index)));
      frame.put("pre_context", truncateLines(lines.subList(from, index)));
      frame.put("post_context", truncateLines(lines.subList(index + 1, to + 1)));
    } catch (IOException | InvalidPathException | SecurityException e) {
      // Best-effort: leave the frame exactly as it was, with no context fields.
    }
  }

  /**
   * Broken out as its own overridable method purely so a test can substitute a counting
   * subclass to prove zero disk reads happen at all when {@code capture_source_context} is
   * gated off: the same subclassing seam {@code ReporterTest}'s {@code ExplodingEventBuilder}
   * already uses elsewhere in this test suite.
   */
  protected List<String> readSourceLines(String file) throws IOException {
    return Files.readAllLines(Path.of(file));
  }

  private static List<String> truncateLines(List<String> lines) {
    List<String> truncated = new ArrayList<>(lines.size());
    for (String line : lines) {
      truncated.add(truncateLine(line));
    }
    return truncated;
  }

  private static String truncateLine(String line) {
    if (line.length() <= MAX_CONTEXT_LINE_LENGTH) {
      return line;
    }
    return line.substring(0, MAX_CONTEXT_LINE_LENGTH) + "...";
  }

  /**
   * {@link StackTraceElement#getFileName()} is always a bare file name ({@code "Foo.java"}),
   * never a full path (verified directly, not assumed) so file-path matching (the approach the
   * Ruby/Python/PHP/Node clients all use) isn't possible here. This client instead compares the
   * frame's declaring class package against a configured prefix, the same role assembly identity
   * plays for the .NET client's own {@code in_app} check.
   */
  private boolean isInApp(StackTraceElement element) {
    String prefix = configuration.getAppPackagePrefix();
    if (prefix == null || prefix.isBlank()) {
      return false;
    }
    return element.getClassName().startsWith(prefix);
  }
}
