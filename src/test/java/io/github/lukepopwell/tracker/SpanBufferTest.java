package io.github.lukepopwell.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpanBufferTest {
  private Configuration configuration() {
    Configuration config = new Configuration();
    config.setEnvironment("production");
    config.setRelease("abc123");
    return config;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> spansOf(Map<String, Object> payload) {
    return (List<Map<String, Object>>) payload.get("spans");
  }

  @Test
  void nestsSpansUnderTheOpenOneAndTheRoot() {
    SpanBuffer buffer = new SpanBuffer(configuration());
    String outer = buffer.open();
    buffer.recordLeaf("SELECT", "database", Instant.now(), 5, Map.of());
    buffer.finish(outer, "charge", "service", Instant.now(), 20, Map.of("k", "v"));

    Map<String, Object> payload = buffer.finishTrace("GET /x", Instant.now(), 1500);
    List<Map<String, Object>> spans = spansOf(payload);

    assertEquals(3, spans.size());
    Map<String, Object> root = spans.get(0);
    assertNull(root.get("parent_span_id"));
    assertEquals("controller", root.get("kind"));
    Map<String, Object> leaf = spans.get(1);
    Map<String, Object> charge = spans.get(2);
    assertEquals(charge.get("span_id"), leaf.get("parent_span_id"));
    assertEquals(root.get("span_id"), charge.get("parent_span_id"));
    assertTrue(((String) payload.get("trace_id")).matches("[0-9a-f]{32}"));
    assertTrue(((String) root.get("span_id")).matches("[0-9a-f]{16}"));
    assertEquals("production", charge.get("environment"));
    assertEquals("abc123", charge.get("release"));
    assertTrue(((String) charge.get("started_at")).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z"));
  }

  @Test
  void continuesACallersTraceWithTheRootParentedUnderTheRemoteSpan() {
    SpanBuffer buffer = new SpanBuffer(configuration(), "4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7");
    Map<String, Object> payload = buffer.finishTrace("GET /x", Instant.now(), 1500);

    assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", payload.get("trace_id"));
    assertEquals("00f067aa0ba902b7", spansOf(payload).get(0).get("parent_span_id"));
  }

  @Test
  void recordsASpanUnderAnIdGeneratedBeforehand() {
    SpanBuffer buffer = new SpanBuffer(configuration());
    String id = buffer.open("1234567890abcdef");
    buffer.finish(id, "GET api.example.com", "http", Instant.now(), 3, Map.of());

    assertEquals("1234567890abcdef", spansOf(buffer.finishTrace("GET /x", Instant.now(), 1500)).get(1).get("span_id"));
  }

  @Test
  void sendsAFastTraceWhenToldToAlways() {
    Configuration config = configuration();
    config.setTraceCaptureThreshold(Duration.ofSeconds(1));
    SpanBuffer buffer = new SpanBuffer(config);
    assertNotNull(buffer.finishTrace("GET /fast", Instant.now(), 5, true));
  }

  @Test
  void dropsTheTraceWhenTheRootIsFasterThanTheThreshold() {
    Configuration config = configuration();
    config.setTraceCaptureThreshold(Duration.ofSeconds(1));
    SpanBuffer buffer = new SpanBuffer(config);
    assertNull(buffer.finishTrace("GET /fast", Instant.now(), 999));
  }

  @Test
  void sendsAnUnknownKindAsOtherSinceTheServerWouldRejectTheWholeTrace() {
    SpanBuffer buffer = new SpanBuffer(configuration());
    buffer.recordLeaf("q", "db", Instant.now(), 1, Map.of());
    buffer.recordLeaf("r", "database", Instant.now(), 1, Map.of());
    List<Map<String, Object>> spans = spansOf(buffer.finishTrace("GET /x", Instant.now(), 1500));
    assertEquals("other", spans.get(1).get("kind"));
    assertEquals("database", spans.get(2).get("kind"));
  }

  @Test
  void capsAtFiveHundredSpansIncludingTheRoot() {
    SpanBuffer buffer = new SpanBuffer(configuration());
    for (int i = 0; i < 700; i++) {
      buffer.recordLeaf("q", "database", Instant.now(), 1, Map.of());
    }
    assertEquals(500, spansOf(buffer.finishTrace("GET /x", Instant.now(), 2000)).size());
  }
}
