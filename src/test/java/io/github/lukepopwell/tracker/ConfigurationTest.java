package io.github.lukepopwell.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ConfigurationTest {
  private static final String DEVELOPMENT_WARNING = "Not sending: this environment is \"development\", and only "
      + "production, staging are enabled. Set FORGE_OPS_ENVIRONMENT=production (or add \"development\" to the "
      + "enabled environments) to send from here.";

  @Test
  void environmentDefaultsToProductionWhenNothingIsSet() {
    assertEquals("production", Configuration.resolveEnvironment(name -> null));
    assertEquals("production", Configuration.resolveEnvironment(name -> "  "));
  }

  @Test
  void environmentComesFromForgeOpsEnvironment() {
    assertEquals("development",
        Configuration.resolveEnvironment(Map.of("FORGE_OPS_ENVIRONMENT", "development")::get));
  }

  @Test
  void springProfilesAreNotAnEnvironment() {
    assertEquals("production",
        Configuration.resolveEnvironment(Map.of("SPRING_PROFILES_ACTIVE", "dev")::get));
  }

  @Test
  void enabledEnvironmentsListProductionFirst() {
    assertEquals(List.of("production", "staging"), new ArrayList<>(new Configuration().getEnabledEnvironments()));
  }

  @Test
  void notSendingWarningExplainsADisabledEnvironment() {
    Configuration config = new Configuration();
    config.setDsn("https://abc123@example.com/api/v1/events");
    config.setEnvironment("development");

    assertEquals(DEVELOPMENT_WARNING, config.notSendingWarning());
  }

  @Test
  void noNotSendingWarningWhenEnabledOrUnconfigured() {
    Configuration config = new Configuration();
    config.setDsn("https://abc123@example.com/api/v1/events");
    config.setEnvironment("staging");
    assertNull(config.notSendingWarning());

    config.setDsn(null);
    config.setEnvironment("development");
    assertNull(config.notSendingWarning());

    config.setDsn("https://abc123@example.com/api/v1/events");
    config.setEnabledEnvironments(Set.of("production", "development"));
    assertNull(config.notSendingWarning());
  }

  @Test
  void warnIfNotSendingPrintsToStderrOnce() {
    Configuration config = new Configuration();
    config.setDsn("https://abc123@example.com/api/v1/events");
    config.setEnvironment("development");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream err = new PrintStream(bytes, true, StandardCharsets.UTF_8);
    AtomicBoolean warned = new AtomicBoolean();

    ForgeOpsTracker.warnIfNotSending(config, warned, err);
    ForgeOpsTracker.warnIfNotSending(config, warned, err);

    assertEquals("[ForgeOps] " + DEVELOPMENT_WARNING + System.lineSeparator(),
        bytes.toString(StandardCharsets.UTF_8));
  }

  @Test
  void warnIfNotSendingUsesTheConfiguredLogger() {
    Configuration config = new Configuration();
    config.setDsn("https://abc123@example.com/api/v1/events");
    config.setEnvironment("development");
    List<String> logged = new ArrayList<>();
    config.setLogger(logged::add);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    ForgeOpsTracker.warnIfNotSending(config, new AtomicBoolean(), new PrintStream(bytes, true, StandardCharsets.UTF_8));

    assertEquals(List.of("[ForgeOpsTracker] " + DEVELOPMENT_WARNING), logged);
    assertEquals(0, bytes.size());
  }

  @Test
  void warnIfNotSendingIsSilentWithoutADsn() {
    Configuration config = new Configuration();
    config.setDsn(null);
    config.setEnvironment("development");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    AtomicBoolean warned = new AtomicBoolean();

    ForgeOpsTracker.warnIfNotSending(config, warned, new PrintStream(bytes, true, StandardCharsets.UTF_8));

    assertEquals(0, bytes.size());
    assertFalse(warned.get());
  }
  @Test
  void parsesApiKeyAndIngestionUriFromDsn() {
    Configuration config = new Configuration();
    config.setDsn("https://abc123@example.com/api/v1/events");

    assertEquals("abc123", config.getApiKey());
    assertEquals("https://example.com/api/v1/events", config.getIngestionUri().toString());
  }

  @Test
  void malformedDsnIsCaughtNotThrown() {
    Configuration config = new Configuration();
    // java.net.URI genuinely throws URISyntaxException on this (verified directly) so
    // Configuration must catch it internally rather than let it propagate to the caller.
    config.setDsn("not a valid uri with spaces and no scheme");

    assertNull(config.getApiKey());
    assertNull(config.getIngestionUri());
    assertFalse(config.isEnabled());
  }

  @Test
  void blankDsnIsNotEnabled() {
    Configuration config = new Configuration();
    config.setDsn(null);
    config.setEnvironment("production");

    assertFalse(config.isEnabled());
  }

  @Test
  void enabledOnlyInConfiguredEnvironments() {
    Configuration config = new Configuration();
    config.setDsn("https://abc123@example.com/api/v1/events");

    config.setEnvironment("development");
    assertFalse(config.isEnabled());

    config.setEnvironment("production");
    assertTrue(config.isEnabled());
  }

  @Test
  void defaultsMirrorTheRubyGem() {
    Configuration config = new Configuration();

    assertTrue(config.getEnabledEnvironments().contains("production"));
    assertTrue(config.getEnabledEnvironments().contains("staging"));
    assertEquals(1000, config.getQueueSize());
    assertTrue(config.isScrubPii());
    assertTrue(config.isCaptureSourceContext());
    assertTrue(config.isTrackSessions());
    assertEquals(java.time.Duration.ofSeconds(60), config.getSessionFlushInterval());
    assertTrue(config.isTrackPerformance());
    assertEquals(java.time.Duration.ofSeconds(60), config.getPerformanceFlushInterval());
    assertTrue(config.isTrackBreadcrumbs());
    assertEquals(30, config.getMaxBreadcrumbs());
  }

  @Test
  void sessionCheckinsUriSwapsEventsForSessionCheckins() {
    Configuration config = new Configuration();
    config.setDsn("https://abc123@example.com/api/v1/events");

    assertEquals("https://example.com/api/v1/session_checkins", config.getSessionCheckinsUri().toString());
  }

  @Test
  void sessionCheckinsUriIsNullWithNoDsn() {
    Configuration config = new Configuration();
    config.setDsn(null);

    assertNull(config.getSessionCheckinsUri());
  }

  @Test
  void performanceSamplesUriSwapsEventsForPerformanceSamples() {
    Configuration config = new Configuration();
    config.setDsn("https://abc123@example.com/api/v1/events");

    assertEquals("https://example.com/api/v1/performance_samples", config.getPerformanceSamplesUri().toString());
  }

  @Test
  void performanceSamplesUriIsNullWithNoDsn() {
    Configuration config = new Configuration();
    config.setDsn(null);

    assertNull(config.getPerformanceSamplesUri());
  }

  @Test
  void propagatesTracesToEveryHostByDefault() {
    Configuration config = new Configuration();

    assertTrue(config.isPropagateTraces());
    assertNull(config.getTracePropagationTargets());
    assertTrue(config.shouldPropagateTrace("api.example.com"));
    assertTrue(config.shouldPropagateTrace(null));
  }

  @Test
  void propagateTracesOffPropagatesNowhere() {
    Configuration config = new Configuration();
    config.setPropagateTraces(false);

    assertFalse(config.shouldPropagateTrace("api.example.com"));
  }

  @Test
  void stringTargetsMatchTheHostAndItsSubdomainsOnADotBoundaryIgnoringCaseAndALeadingDot() {
    Configuration config = new Configuration();
    config.setTracePropagationTargets(List.of("Example.com", ".internal.test"));

    assertTrue(config.shouldPropagateTrace("example.com"));
    assertTrue(config.shouldPropagateTrace("API.example.com"));
    assertTrue(config.shouldPropagateTrace("orders.internal.test"));
    assertFalse(config.shouldPropagateTrace("badexample.com"));
    assertFalse(config.shouldPropagateTrace("example.com.evil.test"));
    assertFalse(config.shouldPropagateTrace(null));
  }

  @Test
  void patternTargetsAreSearchedForInTheLowercasedHost() {
    Configuration config = new Configuration();
    config.setTracePropagationTargets(List.of(Pattern.compile("^10\\.0\\."), Pattern.compile("svc")));

    assertTrue(config.shouldPropagateTrace("10.0.3.4"));
    assertTrue(config.shouldPropagateTrace("orders-SVC.local"));
    assertFalse(config.shouldPropagateTrace("192.10.0.1"));
  }

  @Test
  void anEmptyTargetListOrUnknownTargetTypesMatchNothing() {
    Configuration config = new Configuration();
    config.setTracePropagationTargets(List.of());
    assertFalse(config.shouldPropagateTrace("example.com"));

    config.setTracePropagationTargets(List.of(42, ""));
    assertFalse(config.shouldPropagateTrace("example.com"));
  }
}
