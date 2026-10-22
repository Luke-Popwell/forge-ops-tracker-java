package io.github.lukepopwell.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventBuilderTest {
  @TempDir Path tempDir;
  private Path sourceFile;

  @AfterEach
  void cleanUpSourceFile() throws IOException {
    if (sourceFile != null) {
      Files.deleteIfExists(sourceFile);
    }
  }
  @Test
  void buildsThePayloadShapeTheIngestionApiExpects() {
    Configuration config = new Configuration();
    config.setEnvironment("production");
    config.setRelease("1.2.3");
    config.setServerName("web-1");
    EventBuilder builder = new EventBuilder(config);

    RuntimeException error = new RuntimeException("something broke");
    Map<String, Object> payload = builder.build(error, Map.of("order_id", 42));

    assertEquals("java.lang.RuntimeException", payload.get("exception_class"));
    assertEquals("something broke", payload.get("message"));
    assertEquals("production", payload.get("environment"));
    assertEquals("1.2.3", payload.get("release"));
    assertEquals("web-1", payload.get("server_name"));
    assertTrue(payload.get("occurred_at").toString().endsWith("Z"));
    assertEquals(Map.of("order_id", 42), payload.get("context"));
    assertEquals("java", payload.get("sdk_name"));
  }

  @Test
  void attachesTraceIdTransactionNameAndEndpointUnscrubbedAndLeavesOutNullOnes() {
    Configuration config = new Configuration();
    EventBuilder builder = new EventBuilder(config);
    RuntimeException error = new RuntimeException("x");

    // An endpoint with an email-like route segment would be mangled by the PII scrubber; these are
    // structured fields and must come through exactly as given.
    Map<String, Object> payload = builder.build(error, Map.of(), null, List.of(),
        "4bf92f3577b34da6a3ce929d0e0e4736", "GET /users/{email}", "GET /users/{email}");
    assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", payload.get("trace_id"));
    assertEquals("GET /users/{email}", payload.get("transaction_name"));
    assertEquals("GET /users/{email}", payload.get("endpoint"));

    Map<String, Object> outside = builder.build(error, Map.of(), null, List.of(), null, null, null);
    assertFalse(outside.containsKey("trace_id"));
    assertFalse(outside.containsKey("transaction_name"));
    assertFalse(outside.containsKey("endpoint"));
  }

  @Test
  void backtraceFramesAreStructuredNotRegexParsed() {
    Configuration config = new Configuration();
    EventBuilder builder = new EventBuilder(config);

    RuntimeException error = level1();
    @SuppressWarnings("unchecked")
    List<Object> backtrace = (List<Object>) builder.build(error, Map.of()).get("backtrace");

    assertFalse(backtrace.isEmpty());
    @SuppressWarnings("unchecked")
    Map<String, Object> topFrame = (Map<String, Object>) backtrace.get(0);
    assertEquals("EventBuilderTest.java", topFrame.get("file"));
    assertTrue(topFrame.get("method").toString().contains("level2"));
    assertTrue((Integer) topFrame.get("line") > 0);
  }

  @Test
  void inAppIsPackagePrefixBasedNotFilePathBased() {
    Configuration config = new Configuration();
    // Verified directly: StackTraceElement#getFileName() is always a bare file name, never a
    // full path, so in_app can't be determined by comparing against a path the way the
    // Ruby/Python/PHP/Node clients do: this configures the equivalent package-prefix check.
    config.setAppPackagePrefix("io.github.lukepopwell.tracker");
    EventBuilder builder = new EventBuilder(config);

    RuntimeException error = new RuntimeException("boom");
    @SuppressWarnings("unchecked")
    List<Object> backtrace = (List<Object>) builder.build(error, Map.of()).get("backtrace");
    @SuppressWarnings("unchecked")
    Map<String, Object> topFrame = (Map<String, Object>) backtrace.get(0);

    assertEquals(Boolean.TRUE, topFrame.get("in_app"));
  }

  @Test
  void inAppIsFalseWithNoConfiguredPrefix() {
    Configuration config = new Configuration();
    EventBuilder builder = new EventBuilder(config);

    RuntimeException error = new RuntimeException("boom");
    @SuppressWarnings("unchecked")
    List<Object> backtrace = (List<Object>) builder.build(error, Map.of()).get("backtrace");
    @SuppressWarnings("unchecked")
    Map<String, Object> topFrame = (Map<String, Object>) backtrace.get(0);

    assertEquals(Boolean.FALSE, topFrame.get("in_app"));
  }

  @Test
  void stackTraceIsCapturedAtConstructionNotThrowTime() {
    // Verified directly against the real JVM (fillInStackTrace runs inside every Throwable
    // constructor) before writing this test (same as PHP/Node, unlike Ruby/.NET/Python) so a
    // constructed-but-never-thrown exception still has a full, non-empty backtrace.
    Configuration config = new Configuration();
    EventBuilder builder = new EventBuilder(config);

    RuntimeException neverThrown = new RuntimeException("constructed, never thrown");
    @SuppressWarnings("unchecked")
    List<Object> backtrace = (List<Object>) builder.build(neverThrown, Map.of()).get("backtrace");

    assertFalse(backtrace.isEmpty());
  }

  @Test
  void scrubsPiiFromMessageAndContextByDefault() {
    Configuration config = new Configuration();
    EventBuilder builder = new EventBuilder(config);

    RuntimeException error = new RuntimeException("failed for jane@example.com");
    Map<String, Object> payload = builder.build(error, Map.of("password", "hunter2"));

    assertEquals("failed for [EMAIL FILTERED]", payload.get("message"));
    assertEquals(Map.of("password", "[FILTERED]"), payload.get("context"));
  }

  @Test
  void scrubbingCanBeDisabled() {
    Configuration config = new Configuration();
    config.setScrubPii(false);
    EventBuilder builder = new EventBuilder(config);

    RuntimeException error = new RuntimeException("failed for jane@example.com");
    Map<String, Object> payload = builder.build(error, Map.of());

    assertEquals("failed for jane@example.com", payload.get("message"));
  }

  @Test
  void includesTheUserWhenGivenOneNeverScrubbedEvenThoughItsAnEmail() {
    Configuration config = new Configuration();
    EventBuilder builder = new EventBuilder(config);

    RuntimeException error = new RuntimeException("boom");
    Map<String, Object> payload = builder.build(error, Map.of(), Map.of("id", 42, "email", "ada@example.com"));

    assertEquals(Map.of("id", 42, "email", "ada@example.com"), payload.get("user"));
  }

  @Test
  void omitsTheUserKeyEntirelyWhenNoneWasGiven() {
    Configuration config = new Configuration();
    EventBuilder builder = new EventBuilder(config);

    RuntimeException error = new RuntimeException("boom");
    Map<String, Object> payload = builder.build(error, Map.of());

    assertFalse(payload.containsKey("user"));
  }

  // --- breadcrumbs -----------------------------------------------------------------------------

  @Test
  void includesTheBreadcrumbsWhenGivenSome() {
    Configuration config = new Configuration();
    EventBuilder builder = new EventBuilder(config);
    List<Map<String, Object>> breadcrumbs =
        List.of(Map.of("category", "controller", "message", "GET /orders", "level", "info", "data", Map.of()));

    RuntimeException error = new RuntimeException("boom");
    Map<String, Object> payload = builder.build(error, Map.of(), Map.of(), breadcrumbs);

    assertEquals(breadcrumbs, payload.get("breadcrumbs"));
  }

  @Test
  void omitsTheBreadcrumbsKeyEntirelyWhenNullOrEmpty() {
    Configuration config = new Configuration();
    EventBuilder builder = new EventBuilder(config);

    RuntimeException error = new RuntimeException("boom");
    assertFalse(builder.build(error, Map.of(), Map.of(), null).containsKey("breadcrumbs"));
    assertFalse(builder.build(error, Map.of(), Map.of(), List.of()).containsKey("breadcrumbs"));
  }

  @Test
  void scrubsPiiFromBreadcrumbsUnlikeTheUser() {
    Configuration config = new Configuration();
    EventBuilder builder = new EventBuilder(config);
    List<Map<String, Object>> breadcrumbs =
        List.of(Map.of("category", "custom", "message", "charged jane@example.com", "level", "info", "data", Map.of()));

    RuntimeException error = new RuntimeException("boom");
    Map<String, Object> payload =
        builder.build(error, Map.of(), Map.of("email", "jane@example.com"), breadcrumbs);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> scrubbed = (List<Map<String, Object>>) payload.get("breadcrumbs");
    assertEquals("charged [EMAIL FILTERED]", scrubbed.get(0).get("message"));
    // The user, unlike the breadcrumb above, is deliberately never scrubbed.
    assertEquals("jane@example.com", ((Map<?, ?>) payload.get("user")).get("email"));
  }

  @Test
  void breadcrumbsAreNotScrubbedWhenScrubbingIsDisabled() {
    Configuration config = new Configuration();
    config.setScrubPii(false);
    EventBuilder builder = new EventBuilder(config);
    List<Map<String, Object>> breadcrumbs =
        List.of(Map.of("category", "custom", "message", "charged jane@example.com", "level", "info", "data", Map.of()));

    RuntimeException error = new RuntimeException("boom");
    Map<String, Object> payload = builder.build(error, Map.of(), Map.of(), breadcrumbs);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> unscrubbed = (List<Map<String, Object>>) payload.get("breadcrumbs");
    assertEquals("charged jane@example.com", unscrubbed.get(0).get("message"));
  }

  @Test
  void stillHonorsASubclassOverridingOnlyTheTwoArgumentBuild() {
    Configuration config = new Configuration();
    EventBuilder builder =
        new EventBuilder(config) {
          @Override
          public Map<String, Object> build(Throwable error, Map<String, Object> context) {
            Map<String, Object> payload = new java.util.LinkedHashMap<>(super.build(error, context));
            payload.put("exception_class", "overridden");
            return payload;
          }
        };

    Map<String, Object> payload = builder.build(new RuntimeException("boom"), Map.of(), Map.of("id", 42));

    assertEquals("overridden", payload.get("exception_class"));
    assertEquals(Map.of("id", 42), payload.get("user"));
  }

  private RuntimeException level1() {
    return level2();
  }

  private RuntimeException level2() {
    return new RuntimeException("thrown from level2");
  }

  // --- source context capture ---------------------------------------------------------------

  @Test
  void attachesContextLinePreContextAndPostContextAroundAnInAppFramesCulpritLineByDefault() throws IOException {
    writeSourceFile(20);
    Configuration config = inAppConfig();
    EventBuilder builder = new EventBuilder(config);

    Map<String, Object> frame = frameFor(builder, sourceFile, 10);

    assertEquals("line 10", frame.get("context_line"));
    assertEquals(linesInRange(5, 9), frame.get("pre_context"));
    assertEquals(linesInRange(11, 15), frame.get("post_context"));
  }

  @Test
  void clampsPreContextAndPostContextAtTheStartAndEndOfTheFileRatherThanFailing() throws IOException {
    writeSourceFile(3);
    Configuration config = inAppConfig();
    EventBuilder builder = new EventBuilder(config);

    Map<String, Object> firstFrame = frameFor(builder, sourceFile, 1);
    Map<String, Object> lastFrame = frameFor(builder, sourceFile, 3);

    assertEquals(List.of(), firstFrame.get("pre_context"));
    assertEquals(List.of("line 2", "line 3"), firstFrame.get("post_context"));
    assertEquals(List.of("line 1", "line 2"), lastFrame.get("pre_context"));
    assertEquals(List.of(), lastFrame.get("post_context"));
  }

  @Test
  void truncatesAnIndividualLineLongerThanMaxContextLineLength() throws IOException {
    sourceFile = Files.createTempFile(tempDir, "app_file", ".java");
    Files.writeString(sourceFile, "x".repeat(600));
    Configuration config = inAppConfig();
    EventBuilder builder = new EventBuilder(config);

    Map<String, Object> frame = frameFor(builder, sourceFile, 1);

    assertEquals("x".repeat(500) + "...", frame.get("context_line"));
  }

  @Test
  void neverAttachesContextToAFrameThatIsNotInApp() throws IOException {
    writeSourceFile(20);
    Configuration config = new Configuration(); // no appPackagePrefix configured: not in-app
    EventBuilder builder = new EventBuilder(config);

    Map<String, Object> frame = frameFor(builder, sourceFile, 10);

    assertFalse(frame.containsKey("context_line"));
    assertFalse(frame.containsKey("pre_context"));
    assertFalse(frame.containsKey("post_context"));
  }

  @Test
  void leavesTheFrameUntouchedWithNoFileReadAttemptedWhenCaptureSourceContextIsDisabled() throws IOException {
    writeSourceFile(20);
    Configuration config = inAppConfig();
    config.setCaptureSourceContext(false);
    RecordingEventBuilder builder = new RecordingEventBuilder(config);

    Map<String, Object> frame = frameFor(builder, sourceFile, 10);

    assertFalse(frame.containsKey("context_line"));
    assertEquals(0, builder.reads);
  }

  @Test
  void leavesTheFrameUntouchedWhenTheRecordedFileCannotBeRead() {
    Configuration config = inAppConfig();
    EventBuilder builder = new EventBuilder(config);
    Path missing = tempDir.resolve("does-not-exist-" + UUID.randomUUID() + ".java");

    Map<String, Object> frame = frameFor(builder, missing, 1);

    assertFalse(frame.containsKey("context_line"));
  }

  private Configuration inAppConfig() {
    Configuration config = new Configuration();
    config.setAppPackagePrefix("io.github.lukepopwell.tracker");
    return config;
  }

  private void writeSourceFile(int lineCount) throws IOException {
    sourceFile = Files.createTempFile(tempDir, "app_file", ".java");
    String content = IntStream.rangeClosed(1, lineCount).mapToObj(n -> "line " + n).collect(Collectors.joining("\n"));
    Files.writeString(sourceFile, content);
  }

  private List<String> linesInRange(int from, int to) {
    return IntStream.rangeClosed(from, to).mapToObj(n -> "line " + n).collect(Collectors.toList());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> frameFor(EventBuilder builder, Path file, int line) {
    RuntimeException error = new RuntimeException("boom");
    error.setStackTrace(new StackTraceElement[] {
        new StackTraceElement("io.github.lukepopwell.tracker.SomeClass", "call", file.toString(), line)
    });
    List<Object> backtrace = (List<Object>) builder.build(error, Map.of()).get("backtrace");
    return (Map<String, Object>) backtrace.get(0);
  }

  private static class RecordingEventBuilder extends EventBuilder {
    int reads = 0;

    RecordingEventBuilder(Configuration configuration) {
      super(configuration);
    }

    @Override
    protected List<String> readSourceLines(String file) throws IOException {
      reads++;
      return super.readSourceLines(file);
    }
  }
}
