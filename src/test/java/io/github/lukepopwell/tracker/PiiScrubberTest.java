package io.github.lukepopwell.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PiiScrubberTest {
  @Test
  void redactsEmailAddresses() {
    assertEquals(
        "contact [EMAIL FILTERED] for help",
        PiiScrubber.scrub("contact jane@example.com for help"));
  }

  @Test
  void redactsSsn() {
    assertEquals("ssn is [SSN FILTERED]", PiiScrubber.scrub("ssn is 123-45-6789"));
  }

  @Test
  void redactsCreditCardNumbers() {
    assertEquals(
        "card [CREDIT CARD FILTERED] on file",
        PiiScrubber.scrub("card 4111 1111 1111 1111 on file"));
  }

  @Test
  void redactsBearerTokens() {
    assertEquals(
        "auth: [BEARER TOKEN FILTERED]",
        PiiScrubber.scrub("auth: Bearer abc123XYZ.def456"));
  }

  @Test
  void redactsStripeKeys() {
    assertEquals(
        "key=[STRIPE KEY FILTERED]",
        PiiScrubber.scrub("key=sk_live_abcdefghijklmnop"));
  }

  @Test
  void redactsValuesUnderSensitiveKeyNamesRegardlessOfShape() {
    assertEquals("[FILTERED]", PiiScrubber.scrub("hunter2", "password"));
    assertEquals("[FILTERED]", PiiScrubber.scrub("hunter2", "user_password"));
    assertEquals("[FILTERED]", PiiScrubber.scrub("sk_live_abc", "apiKey"));
  }

  @Test
  void recursesThroughMapsAndLists() {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("password", "hunter2");
    input.put("emails", List.of("a@example.com", "b@example.com"));
    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put("ssn", "123-45-6789");
    input.put("nested", nested);

    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) PiiScrubber.scrub(input);

    assertEquals("[FILTERED]", result.get("password"));
    assertEquals(List.of("[EMAIL FILTERED]", "[EMAIL FILTERED]"), result.get("emails"));
    @SuppressWarnings("unchecked")
    Map<String, Object> resultNested = (Map<String, Object>) result.get("nested");
    assertEquals("[FILTERED]", resultNested.get("ssn"));
  }

  @Test
  void leavesUnsuspiciousContentAlone() {
    assertEquals("everything is fine", PiiScrubber.scrub("everything is fine"));
  }

  @Test
  void nonStringValuesPassThroughUnscrubbedUnlessKeyIsSensitive() {
    assertEquals(42, PiiScrubber.scrub(42, "count"));
    assertEquals("[FILTERED]", PiiScrubber.scrub(42, "apiToken"));
  }

  // pii_scrubbing_vectors.json is a byte-identical copy of sdks/pii_scrubbing_vectors.json,
  // generated from the server's own PiiScrubber: see sdks/PII_SCRUBBING.md.
  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> vectors(String kind) throws IOException {
    try (InputStream in = PiiScrubberTest.class.getResourceAsStream("/pii_scrubbing_vectors.json")) {
      String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      Map<String, Object> all = (Map<String, Object>) new StrictPrivacyTest.JsonReader(json).read();
      return (List<Map<String, Object>>) all.get(kind);
    }
  }

  @Test
  void scrubsCardNumbersAndIbansInEveryVectorExactlyAsTheServerDoes() throws IOException {
    List<Map<String, Object>> vectors = vectors("strings");
    assertFalse(vectors.isEmpty());
    for (Map<String, Object> vector : vectors) {
      assertEquals(vector.get("scrubbed"), PiiScrubber.scrub(vector.get("input")), (String) vector.get("input"));
    }
  }

  @Test
  void redactsBankDetailKeysInEveryVectorExactlyAsTheServerDoes() throws IOException {
    List<Map<String, Object>> vectors = vectors("keys");
    assertFalse(vectors.isEmpty());
    for (Map<String, Object> vector : vectors) {
      String key = (String) vector.get("key");
      @SuppressWarnings("unchecked")
      Map<String, Object> result = (Map<String, Object>) PiiScrubber.scrub(Map.of(key, "x"));
      assertEquals(Boolean.TRUE.equals(vector.get("redacted")) ? "[FILTERED]" : "x", result.get(key), key);
    }
  }
}
