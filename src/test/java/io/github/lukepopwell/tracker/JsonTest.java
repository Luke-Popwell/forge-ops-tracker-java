package io.github.lukepopwell.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {
  @Test
  void encodesPrimitives() {
    assertEquals("null", Json.encode(null));
    assertEquals("true", Json.encode(true));
    assertEquals("42", Json.encode(42));
    assertEquals("\"hello\"", Json.encode("hello"));
  }

  @Test
  void escapesSpecialCharactersInStrings() {
    assertEquals("\"line1\\nline2\\ttabbed\\\"quoted\\\"\"", Json.encode("line1\nline2\ttabbed\"quoted\""));
  }

  @Test
  void encodesNestedMapsAndLists() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("a", 1);
    payload.put("b", List.of("x", "y"));
    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put("c", null);
    payload.put("nested", nested);

    assertEquals("{\"a\":1,\"b\":[\"x\",\"y\"],\"nested\":{\"c\":null}}", Json.encode(payload));
  }

  @Test
  void preservesInsertionOrderOfMapKeys() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("z", 1);
    payload.put("a", 2);

    assertEquals("{\"z\":1,\"a\":2}", Json.encode(payload));
  }
}
