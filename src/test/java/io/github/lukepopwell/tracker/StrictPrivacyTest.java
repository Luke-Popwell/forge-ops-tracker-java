package io.github.lukepopwell.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Checks this client against {@code strict_privacy_vectors.json} (a byte-identical copy of {@code
 * sdks/strict_privacy_vectors.json}, generated from the server's own {@code StrictPrivacy}), and
 * that strict privacy strips every free-text field from an event while leaving events exactly as
 * before when it's off.
 */
class StrictPrivacyTest {
  private static final String EMAIL_MESSAGE = "Couldn't find patient jane@example.com with id=8821";
  // The fingerprint is taken from the message as it would otherwise have been sent: scrubbed.
  private static final String SCRUBBED_MESSAGE = "Couldn't find patient [EMAIL FILTERED] with id=8821";

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> vectors(String kind) throws IOException {
    try (InputStream in = StrictPrivacyTest.class.getResourceAsStream("/strict_privacy_vectors.json")) {
      String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      Map<String, Object> all = (Map<String, Object>) new JsonReader(json).read();
      return (List<Map<String, Object>>) all.get(kind);
    }
  }

  @Test
  void normalizesAndFingerprintsEveryVectorExactlyAsTheServerDoes() throws IOException {
    List<Map<String, Object>> vectors = vectors("fingerprint");
    assertFalse(vectors.isEmpty());
    for (Map<String, Object> vector : vectors) {
      String message = (String) vector.get("message");
      assertEquals(vector.get("normalized"), StrictPrivacy.normalizeMessage(message), message);
      assertEquals(vector.get("fingerprint"), StrictPrivacy.messageFingerprint(message), message);
    }
  }

  @Test
  void anAbsentMessageFingerprintsAsTheEmptyOne() {
    assertEquals(StrictPrivacy.messageFingerprint(""), StrictPrivacy.messageFingerprint(null));
  }

  @Test
  void takesIdsOutOfEveryPathVector() throws IOException {
    for (Map<String, Object> vector : vectors("path")) {
      assertEquals(vector.get("strict"), StrictPrivacy.strictPath((String) vector.get("path")),
          (String) vector.get("path"));
    }
  }

  @Test
  void namesEverySpanVectorByMethodAndHostOnly() throws IOException {
    for (Map<String, Object> vector : vectors("span_name")) {
      assertEquals(vector.get("strict"), StrictPrivacy.spanName((String) vector.get("name")),
          (String) vector.get("name"));
    }
  }

  @Test
  void isOffByDefaultAndReadFromTheEnvironment() {
    Configuration config = new Configuration();
    assertEquals(Configuration.resolveStrictPrivacy(System::getenv), config.isStrictPrivacy());
    assertFalse(Configuration.resolveStrictPrivacy(name -> null));
    assertTrue(Configuration.resolveStrictPrivacy(Map.of("FORGE_OPS_STRICT_PRIVACY", "true")::get));
    assertTrue(Configuration.resolveStrictPrivacy(Map.of("FORGE_OPS_STRICT_PRIVACY", "TRUE")::get));
    assertFalse(Configuration.resolveStrictPrivacy(Map.of("FORGE_OPS_STRICT_PRIVACY", "false")::get));
    assertEquals(Set.of(), Configuration.resolvePrivacyAllowedFields(name -> null));
    assertEquals(List.of("error_code", "feature"), new ArrayList<>(Configuration.resolvePrivacyAllowedFields(
        Map.of("FORGE_OPS_PRIVACY_ALLOWED_FIELDS", "error_code, feature,,")::get)));
  }

  @Test
  void anExplicitOptionWinsOverTheEnvironment() {
    Configuration config = new Configuration();
    config.setStrictPrivacy(true);
    config.setPrivacyAllowedFields(List.of("error_code"));
    assertTrue(config.isStrictPrivacy());
    assertEquals(Set.of("error_code"), config.getPrivacyAllowedFields());
    config.setStrictPrivacy(false);
    assertFalse(config.isStrictPrivacy());
  }

  @Test
  void aStrictEventCarriesNoFreeText() {
    Configuration config = configuration();
    config.setStrictPrivacy(true);
    config.setPrivacyAllowedFields(List.of("error_code", "retries", "flagged", "nested"));
    CountingEventBuilder builder = new CountingEventBuilder(config);

    Map<String, Object> payload = build(builder);

    assertEquals("", payload.get("message"));
    assertEquals(StrictPrivacy.messageFingerprint(SCRUBBED_MESSAGE), payload.get("message_fingerprint"));
    assertTrue(((String) payload.get("message_fingerprint")).matches("[0-9a-f]{32}"));
    assertEquals(Map.of("error_code", "E42", "retries", 3, "flagged", true), payload.get("context"));
    assertFalse(payload.containsKey("tags"));
    assertEquals(Map.of("id", "42"), payload.get("user"));
    assertFalse(payload.containsKey("breadcrumbs"));
    assertFalse(payload.containsKey("endpoint"));
    assertFalse(payload.containsKey("sql_statement"));
    assertEquals(0, builder.reads);
    for (Object frame : (List<?>) payload.get("backtrace")) {
      Map<?, ?> fields = (Map<?, ?>) frame;
      assertFalse(fields.containsKey("context_line"));
      assertFalse(fields.containsKey("pre_context"));
      assertFalse(fields.containsKey("post_context"));
    }

    // The structured fields stay exactly as they are without strict privacy.
    assertEquals(SqlStatementTest.FakeJdbcException.class.getName(), payload.get("exception_class"));
    assertEquals("GET /patients/{id}", payload.get("transaction_name"));
    assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", payload.get("trace_id"));
    assertEquals("production", payload.get("environment"));
    assertEquals("1.2.3", payload.get("release"));
    assertEquals("java", payload.get("sdk_name"));
    assertTrue(payload.containsKey("sql_objects"));
    assertFalse(Json.encode(payload).contains("jane"));
  }

  @Test
  void aStrictEventLeavesOutContextAndUserWhenNothingIsAllowed() {
    Configuration config = configuration();
    config.setStrictPrivacy(true);
    Map<String, Object> payload = new EventBuilder(config).build(new RuntimeException("x"),
        Map.of("error_code", "E42"), Map.of("email", "jane@example.com"));
    assertFalse(payload.containsKey("context"));
    assertFalse(payload.containsKey("tags"));
    assertFalse(payload.containsKey("user"));

    Map<String, Object> noMessage = new EventBuilder(config).build(new RuntimeException(), Map.of());
    assertEquals(StrictPrivacy.messageFingerprint(""), noMessage.get("message_fingerprint"));
  }

  @Test
  void aStrictEventsRawPathTransactionNameLosesItsIdsAndARouteIsKept() {
    Configuration config = configuration();
    config.setStrictPrivacy(true);
    EventBuilder builder = new EventBuilder(config);
    RuntimeException error = new RuntimeException("x");
    assertEquals("GET /patients/:path", builder.build(error, Map.of(), null, List.of(), null,
        "GET /patients/42/notes", null).get("transaction_name"));
    assertEquals("OrdersController#create", builder.build(error, Map.of(), null, List.of(), null,
        "OrdersController#create", null).get("transaction_name"));
    assertEquals("GET /v1/orders/{id}", builder.build(error, Map.of(), null, List.of(), null,
        "GET /v1/orders/{id}", null).get("transaction_name"));

    config.setStrictPrivacy(false);
    assertEquals("GET /patients/42/notes", builder.build(error, Map.of(), null, List.of(), null,
        "GET /patients/42/notes", null).get("transaction_name"));
  }

  @Test
  void withStrictPrivacyOffEventsAreExactlyAsBefore() {
    Configuration config = configuration();
    CountingEventBuilder builder = new CountingEventBuilder(config);

    Map<String, Object> payload = build(builder);

    assertEquals(List.of("exception_class", "message", "backtrace", "occurred_at", "environment", "release",
        "server_name", "context", "tags", "sdk_name", "sql_objects", "sql_statement", "user", "breadcrumbs",
        "transaction_name", "endpoint", "trace_id"), new ArrayList<>(payload.keySet()));
    assertEquals(SCRUBBED_MESSAGE, payload.get("message"));
    assertEquals(Map.of("error_code", "E42", "retries", 3, "flagged", true, "nested", Map.of("a", 1),
        "note", "free text"), payload.get("context"));
    assertEquals(Map.of(), payload.get("tags"));
    assertEquals(Map.of("id", 42, "email", "jane@example.com", "username", "jane"), payload.get("user"));
    assertEquals(1, ((List<?>) payload.get("breadcrumbs")).size());
    assertEquals("GET /patients/{id}", payload.get("endpoint"));
    assertEquals("SELECT * FROM patients WHERE id = ?", payload.get("sql_statement"));
    assertTrue(builder.reads > 0);
  }

  @Test
  void strictPrivacyRecordsNoBreadcrumbs() {
    Configuration config = configuration();
    config.setStrictPrivacy(true);
    BreadcrumbBuffer buffer = new BreadcrumbBuffer(config);
    buffer.add("custom", "looked up jane@example.com", "info", Map.of());
    assertEquals(List.of(), buffer.all());

    config.setStrictPrivacy(false);
    buffer.add("custom", "looked up", "info", Map.of());
    assertEquals(1, buffer.all().size());
  }

  @Test
  @SuppressWarnings("unchecked")
  void strictSpansKeepOnlyMethodHostAndDatabaseData() {
    Configuration config = configuration();
    config.setStrictPrivacy(true);
    SpanBuffer buffer = new SpanBuffer(config);
    buffer.recordLeaf("GET https://api.example.com/patients/42?x=1", "http", Instant.now(), 5,
        Map.of("url", "https://api.example.com/patients/42", "status", 200));
    buffer.recordLeaf("Load patient", "database", Instant.now(), 5,
        Map.of("db.statement", "SELECT * FROM patients WHERE name = 'jane'", "db.system", "postgresql", "rows", 1));
    List<Map<String, Object>> spans =
        (List<Map<String, Object>>) buffer.finishTrace("GET /patients/42", Instant.now(), 5000).get("spans");

    assertEquals("GET /patients/:path", spans.get(0).get("name"));
    assertEquals(Map.of(), spans.get(0).get("data"));
    assertEquals("GET api.example.com", spans.get(1).get("name"));
    assertEquals(Map.of(), spans.get(1).get("data"));
    assertEquals("Load patient", spans.get(2).get("name"));
    assertEquals(Map.of("db.statement", "SELECT * FROM patients WHERE name = ?", "db.system", "postgresql"),
        spans.get(2).get("data"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void spansAreUnchangedWithStrictPrivacyOff() {
    SpanBuffer buffer = new SpanBuffer(configuration());
    buffer.recordLeaf("GET https://api.example.com/patients/42", "http", Instant.now(), 5, Map.of("status", 200));
    List<Map<String, Object>> spans =
        (List<Map<String, Object>>) buffer.finishTrace("GET /patients/42", Instant.now(), 5000).get("spans");
    assertEquals("GET /patients/42", spans.get(0).get("name"));
    assertEquals("GET https://api.example.com/patients/42", spans.get(1).get("name"));
    assertEquals(Map.of("status", 200), spans.get(1).get("data"));
  }

  private static Configuration configuration() {
    Configuration config = new Configuration();
    config.setEnvironment("production");
    config.setRelease("1.2.3");
    config.setServerName("web-1");
    config.setStrictPrivacy(false);
    config.setPrivacyAllowedFields(List.of());
    config.setCaptureSqlStatement(true);
    config.setAppPackagePrefix("io.github.lukepopwell");
    return config;
  }

  private static Map<String, Object> build(EventBuilder builder) {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("error_code", "E42");
    context.put("retries", 3);
    context.put("flagged", true);
    context.put("nested", Map.of("a", 1));
    context.put("note", "free text");
    Map<String, Object> user = new LinkedHashMap<>();
    user.put("id", 42);
    user.put("email", "jane@example.com");
    user.put("username", "jane");
    List<Map<String, Object>> breadcrumbs =
        List.of(Map.of("category", "custom", "message", "opened jane's chart", "level", "info"));
    RuntimeException error =
        new SqlStatementTest.FakeJdbcException(EMAIL_MESSAGE, "SELECT * FROM patients WHERE id = 8821");
    return builder.build(error, context, user, breadcrumbs, "4bf92f3577b34da6a3ce929d0e0e4736",
        "GET /patients/{id}", "GET /patients/{id}");
  }

  /** Counts source reads, and pretends every file has enough lines, so source context would be attached. */
  private static final class CountingEventBuilder extends EventBuilder {
    int reads;

    CountingEventBuilder(Configuration configuration) {
      super(configuration);
    }

    @Override
    protected List<String> readSourceLines(String file) {
      reads++;
      List<String> lines = new ArrayList<>();
      for (int i = 0; i < 5000; i++) {
        lines.add("line " + i);
      }
      return lines;
    }
  }

  /**
   * Just enough JSON for the vectors files: objects, arrays, strings and the escapes they use, and
   * booleans. Also read by {@link PiiScrubberTest}.
   */
  static final class JsonReader {
    private final String json;
    private int at;

    JsonReader(String json) {
      this.json = json;
    }

    Object read() {
      skipWhitespace();
      char c = json.charAt(at);
      if (c == '{') {
        Map<String, Object> object = new LinkedHashMap<>();
        at++;
        skipWhitespace();
        if (json.charAt(at) == '}') {
          at++;
          return object;
        }
        while (true) {
          skipWhitespace();
          String key = (String) read();
          skipWhitespace();
          at++; // ':'
          object.put(key, read());
          skipWhitespace();
          if (json.charAt(at++) == '}') {
            return object;
          }
        }
      }
      if (c == '[') {
        List<Object> array = new ArrayList<>();
        at++;
        skipWhitespace();
        if (json.charAt(at) == ']') {
          at++;
          return array;
        }
        while (true) {
          array.add(read());
          skipWhitespace();
          if (json.charAt(at++) == ']') {
            return array;
          }
        }
      }
      if (c == '"') {
        StringBuilder out = new StringBuilder();
        at++;
        while (json.charAt(at) != '"') {
          char next = json.charAt(at++);
          if (next != '\\') {
            out.append(next);
            continue;
          }
          char escape = json.charAt(at++);
          switch (escape) {
            case 'n' -> out.append('\n');
            case 't' -> out.append('\t');
            case 'r' -> out.append('\r');
            case 'b' -> out.append('\b');
            case 'f' -> out.append('\f');
            case 'u' -> {
              out.append((char) Integer.parseInt(json.substring(at, at + 4), 16));
              at += 4;
            }
            default -> out.append(escape);
          }
        }
        at++;
        return out.toString();
      }
      if (json.startsWith("true", at)) {
        at += 4;
        return Boolean.TRUE;
      }
      if (json.startsWith("false", at)) {
        at += 5;
        return Boolean.FALSE;
      }
      throw new IllegalArgumentException("unexpected JSON at " + at);
    }

    private void skipWhitespace() {
      while (at < json.length() && Character.isWhitespace(json.charAt(at))) {
        at++;
      }
    }
  }
}
