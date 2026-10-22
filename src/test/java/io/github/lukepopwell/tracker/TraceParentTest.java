package io.github.lukepopwell.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TraceParentTest {
  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
  private static final String SPAN_ID = "00f067aa0ba902b7";

  @Test
  void parsesAValidVersion00Header() {
    TraceParent.Context context = TraceParent.parse("00-" + TRACE_ID + "-" + SPAN_ID + "-01");

    assertEquals(TRACE_ID, context.traceId());
    assertEquals(SPAN_ID, context.parentSpanId());
  }

  @Test
  void toleratesSurroundingWhitespaceAndAnyFlags() {
    TraceParent.Context context = TraceParent.parse("  00-" + TRACE_ID + "-" + SPAN_ID + "-00 ");

    assertEquals(TRACE_ID, context.traceId());
  }

  @Test
  void acceptsAFutureVersionWithExtraFields() {
    TraceParent.Context context = TraceParent.parse("cc-" + TRACE_ID + "-" + SPAN_ID + "-01-what-comes-next");

    assertEquals(TRACE_ID, context.traceId());
  }

  @Test
  void rejectsExtraFieldsOnVersion00() {
    assertNull(TraceParent.parse("00-" + TRACE_ID + "-" + SPAN_ID + "-01-extra"));
  }

  @Test
  void rejectsTheReservedVersionFf() {
    assertNull(TraceParent.parse("ff-" + TRACE_ID + "-" + SPAN_ID + "-01"));
  }

  @Test
  void rejectsAllZeroIds() {
    assertNull(TraceParent.parse("00-" + "0".repeat(32) + "-" + SPAN_ID + "-01"));
    assertNull(TraceParent.parse("00-" + TRACE_ID + "-" + "0".repeat(16) + "-01"));
  }

  @Test
  void rejectsUppercaseHexAndMalformedValues() {
    assertNull(TraceParent.parse("00-" + TRACE_ID.toUpperCase() + "-" + SPAN_ID + "-01"));
    assertNull(TraceParent.parse("00-" + TRACE_ID + "-" + SPAN_ID));
    assertNull(TraceParent.parse("00-" + TRACE_ID.substring(1) + "-" + SPAN_ID + "-01"));
    assertNull(TraceParent.parse("0-" + TRACE_ID + "-" + SPAN_ID + "-01"));
    assertNull(TraceParent.parse("garbage"));
    assertNull(TraceParent.parse(""));
    assertNull(TraceParent.parse(null));
  }

  @Test
  void buildsASampledVersion00Header() {
    assertEquals("00-" + TRACE_ID + "-" + SPAN_ID + "-01", TraceParent.build(TRACE_ID, SPAN_ID));
  }

  @Test
  void generatesLowercaseHexIdsOfTheRightLengthThatRoundTrip() {
    String traceId = TraceParent.generateTraceId();
    String spanId = TraceParent.generateSpanId();

    assertTrue(traceId.matches("[0-9a-f]{32}"));
    assertTrue(spanId.matches("[0-9a-f]{16}"));
    assertNotEquals("0".repeat(32), traceId);
    assertNotEquals(traceId, TraceParent.generateTraceId());
    assertEquals(traceId, TraceParent.parse(TraceParent.build(traceId, spanId)).traceId());
  }
}
