package io.github.lukepopwell.tracker;

import java.security.SecureRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads and writes the W3C Trace Context {@code traceparent} header
 * (https://www.w3.org/TR/trace-context/), the vendor-neutral format for carrying one trace across
 * service boundaries: {@code 00-<32 hex trace id>-<16 hex parent span id>-<2 hex flags>}. Mirrors
 * {@code gems/forge_ops_tracker}'s own {@code TraceParent}: {@link #parse} lets a request continue
 * its caller's trace, and {@link #build} is what {@link ForgeOpsTracker#httpSpan} hands the next
 * service along.
 *
 * <p>Strict on the way in, the posture the spec asks receivers to take: a malformed value, uppercase
 * hex, the reserved version {@code ff}, or an all-zero trace or parent id all mean "no usable
 * header" ({@link #parse} returns {@code null} and a fresh trace starts), never half-trusted. A
 * future version is still accepted when its first four fields have version 00's shape; version 00
 * itself must have exactly four.
 */
public final class TraceParent {
  public static final String HEADER = "traceparent";

  /** A usable incoming header's two ids: the caller's trace, and the caller's span this work runs under. */
  public record Context(String traceId, String parentSpanId) {}

  private static final Pattern PATTERN =
      Pattern.compile("([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})(-.*)?");
  private static final String INVALID_TRACE_ID = "0".repeat(32);
  private static final String INVALID_SPAN_ID = "0".repeat(16);

  // Always "01" (sampled) on the way out: whether a trace is sent is only decided once it's over
  // (see Configuration#getTraceCaptureThreshold), long after this header has gone out, so "this may
  // be recorded" is the only honest answer. The next service makes its own decision either way.
  private static final String SAMPLED_FLAGS = "01";

  private static final SecureRandom RANDOM = new SecureRandom();

  private TraceParent() {}

  public static Context parse(String value) {
    if (value == null) {
      return null;
    }
    Matcher match = PATTERN.matcher(value.trim());
    if (!match.matches()) {
      return null;
    }
    String version = match.group(1);
    String traceId = match.group(2);
    String parentId = match.group(3);
    if (version.equals("ff")) {
      return null;
    }
    if (version.equals("00") && match.group(5) != null) {
      return null;
    }
    if (traceId.equals(INVALID_TRACE_ID) || parentId.equals(INVALID_SPAN_ID)) {
      return null;
    }
    return new Context(traceId, parentId);
  }

  public static String build(String traceId, String spanId) {
    return "00-" + traceId + "-" + spanId + "-" + SAMPLED_FLAGS;
  }

  /** 32 lowercase hex characters, never all zeros (the spec's one invalid value). */
  public static String generateTraceId() {
    return randomNonZeroHex(16);
  }

  /** 16 lowercase hex characters, never all zeros. */
  public static String generateSpanId() {
    return randomNonZeroHex(8);
  }

  private static String randomNonZeroHex(int bytes) {
    byte[] raw = new byte[bytes];
    do {
      RANDOM.nextBytes(raw);
    } while (allZero(raw));
    StringBuilder out = new StringBuilder(bytes * 2);
    for (byte b : raw) {
      out.append(String.format("%02x", b));
    }
    return out.toString();
  }

  private static boolean allZero(byte[] raw) {
    for (byte b : raw) {
      if (b != 0) {
        return false;
      }
    }
    return true;
  }
}
