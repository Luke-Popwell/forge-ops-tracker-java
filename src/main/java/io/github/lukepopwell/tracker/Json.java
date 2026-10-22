package io.github.lukepopwell.tracker;

import java.util.List;
import java.util.Map;

/**
 * A minimal, dependency-free JSON encoder for exactly the payload shapes this client produces:
 * nested {@link Map}/{@link List} of {@link String}/{@link Number}/{@link Boolean}/{@code null}.
 *
 * <p>Every other client here reaches for its language's own standard library ({@code json} in
 * Ruby/Python, {@code json_encode} in PHP, {@code JSON.stringify} in Node): the JDK is the one
 * standard library in this set with no built-in JSON support, so this class is this client's
 * equivalent: a genuine reason to write ~60 lines here that none of the other four clients needed,
 * not a missing dependency to add. It only needs to encode, never parse: this client never reads
 * JSON back.
 */
final class Json {
  private Json() {}

  static String encode(Object value) {
    StringBuilder out = new StringBuilder();
    write(value, out);
    return out.toString();
  }

  private static void write(Object value, StringBuilder out) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof Map<?, ?> map) {
      writeMap(map, out);
    } else if (value instanceof List<?> list) {
      writeList(list, out);
    } else if (value instanceof String string) {
      writeString(string, out);
    } else if (value instanceof Boolean || value instanceof Number) {
      out.append(value);
    } else {
      // Anything else (a caller-supplied context object we don't recognize) is stringified
      // rather than dropped or thrown on: best-effort, never a reason to fail delivery.
      writeString(value.toString(), out);
    }
  }

  private static void writeMap(Map<?, ?> map, StringBuilder out) {
    out.append('{');
    boolean first = true;
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!first) {
        out.append(',');
      }
      first = false;
      writeString(String.valueOf(entry.getKey()), out);
      out.append(':');
      write(entry.getValue(), out);
    }
    out.append('}');
  }

  private static void writeList(List<?> list, StringBuilder out) {
    out.append('[');
    boolean first = true;
    for (Object item : list) {
      if (!first) {
        out.append(',');
      }
      first = false;
      write(item, out);
    }
    out.append(']');
  }

  private static void writeString(String value, StringBuilder out) {
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}
