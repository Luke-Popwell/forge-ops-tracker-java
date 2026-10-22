package io.github.lukepopwell.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs {@link ExitFlushMain} in a real child JVM, since only a JVM that actually exits shows
 * whether an error reported in its last moments still goes out.
 */
class ExitFlushTest {
  @TempDir
  Path tempDir;

  @Test
  void sendsAnErrorReportedJustBeforeMainReturns() throws Exception {
    Result result = run("return");
    assertEquals(0, result.exitCode());
    assertEquals("reported just before exit\n", result.delivered());
  }

  @Test
  void sendsAnErrorReportedJustBeforeSystemExit() throws Exception {
    Result result = run("exit");
    assertEquals(3, result.exitCode());
    assertEquals("reported before System.exit\n", result.delivered());
  }

  @Test
  void sendsTheUncaughtExceptionThatEndedMain() throws Exception {
    Result result = run("uncaught");
    assertEquals(1, result.exitCode());
    assertEquals("crashed main\n", result.delivered());
  }

  @Test
  void flushSendsBeforeHaltWhichSkipsShutdownHooks() throws Exception {
    Result result = run("flush-then-halt");
    assertEquals(0, result.exitCode());
    assertEquals("flushed before halt\n", result.delivered());
  }

  @Test
  void haltWithoutFlushLosesTheError() throws Exception {
    // The control for the tests above: the fake client is slow enough that the error never goes
    // out unless something waits for it.
    Result result = run("halt");
    assertEquals(0, result.exitCode());
    assertEquals("", result.delivered());
  }

  @Test
  void aDsnAloneSendsTheFirstError() throws Exception {
    // Following the setup page exactly: a DSN and nothing else, so the environment defaults to
    // production and the error goes out with no warning.
    Result result = run("setup-page", Map.of("FORGE_OPS_DSN", "https://key@example.com/api/v1/events"));
    assertEquals(0, result.exitCode());
    assertEquals("first test error\n", result.delivered());
    assertFalse(result.output().contains("Not sending"), result.output());
  }

  @Test
  void aDevelopmentEnvironmentSendsNothingAndSaysSoOnce() throws Exception {
    Result result = run("setup-page", Map.of(
        "FORGE_OPS_DSN", "https://key@example.com/api/v1/events",
        "FORGE_OPS_ENVIRONMENT", "development"));
    assertEquals(0, result.exitCode());
    assertEquals("", result.delivered());
    String warning = "[ForgeOps] Not sending: this environment is \"development\", and only production, staging "
        + "are enabled. Set FORGE_OPS_ENVIRONMENT=production (or add \"development\" to the enabled environments) "
        + "to send from here.";
    assertTrue(result.output().contains(warning), result.output());
    assertEquals(result.output().indexOf("Not sending"), result.output().lastIndexOf("Not sending"),
        "warning printed more than once: " + result.output());
  }

  private record Result(int exitCode, String delivered, String output) {}

  private Result run(String mode) throws IOException, InterruptedException {
    return run(mode, Map.of());
  }

  /**
   * {@code env} is added to a child environment with every {@code FORGE_OPS_*} variable removed,
   * so whatever the machine running the tests has set can't change what the child does.
   */
  private Result run(String mode, Map<String, String> env) throws IOException, InterruptedException {
    Path out = Files.createFile(tempDir.resolve("delivered.txt"));
    Path output = tempDir.resolve("output.txt");
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    ProcessBuilder builder = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
        ExitFlushMain.class.getName(), mode, out.toString())
        .redirectErrorStream(true)
        .redirectOutput(output.toFile());
    builder.environment().keySet().removeIf(name -> name.startsWith("FORGE_OPS_"));
    builder.environment().putAll(env);
    Process process = builder.start();
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "child JVM did not exit");
    return new Result(process.exitValue(), Files.readString(out), Files.readString(output));
  }
}
