package io.github.lukepopwell.tracker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code recordChange} and the startup change snapshot, against a local stub server only. */
class ChangeTrackerTest {
  private HttpServer server;
  private int status = 202;
  private final BlockingQueue<String> changes = new LinkedBlockingQueue<>();
  private final BlockingQueue<String> snapshots = new LinkedBlockingQueue<>();

  @BeforeEach
  void start() throws IOException {
    ForgeOpsTracker.resetForTesting();
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/api/v1/changes", exchange -> {
      changes.add(new String(exchange.getRequestBody().readAllBytes()));
      exchange.sendResponseHeaders(status, -1);
      exchange.close();
    });
    server.createContext("/api/v1/change_snapshots", exchange -> {
      snapshots.add(new String(exchange.getRequestBody().readAllBytes()));
      exchange.sendResponseHeaders(status, -1);
      exchange.close();
    });
    server.start();
  }

  @AfterEach
  void stop() {
    ForgeOpsTracker.resetForTesting();
    server.stop(0);
  }

  private void init(Consumer<Configuration> extra) {
    ForgeOpsTracker.init(config -> {
      config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
      config.setEnvironment("production");
      extra.accept(config);
    }, false);
  }

  private static Configuration enabledConfiguration() {
    Configuration config = new Configuration();
    config.setDsn("https://key@tracker.example.com/api/v1/events");
    config.setEnvironment("production");
    return config;
  }

  @Test
  void changePayloadCarriesEveryFieldThatWasSet() {
    Map<String, Object> payload = ChangeTracker.changePayload(
        Change.of("feature_flag", "Enabled new checkout")
            .details(Map.of("flag", "new_checkout"))
            .environment("staging")
            .service("billing")
            .actor("luke")
            .url("https://github.com/acme/app/pull/12")
            .id("flag-new-checkout-1")
            .occurredAt(Instant.parse("2026-09-25T12:00:00Z")),
        enabledConfiguration());

    Map<String, Object> expected = new LinkedHashMap<>();
    expected.put("kind", "feature_flag");
    expected.put("title", "Enabled new checkout");
    expected.put("details", Map.of("flag", "new_checkout"));
    expected.put("environment", "staging");
    expected.put("service", "billing");
    expected.put("actor", "luke");
    expected.put("url", "https://github.com/acme/app/pull/12");
    expected.put("occurred_at", "2026-09-25T12:00:00Z");
    expected.put("id", "flag-new-checkout-1");
    assertEquals(expected, payload);
  }

  @Test
  void changePayloadDefaultsEnvironmentAndTimeAndLeavesTheRestOut() {
    Map<String, Object> payload = ChangeTracker.changePayload(Change.of("config", "Raised pool size"),
        enabledConfiguration());

    assertEquals(List.of("kind", "title", "environment", "occurred_at"), List.copyOf(payload.keySet()));
    assertEquals("production", payload.get("environment"));
    assertNotNull(Instant.parse((String) payload.get("occurred_at")));
  }

  @Test
  void unknownOrMissingKindsBecomeOther() {
    assertEquals("migration", ChangeTracker.normalizeKind("migration"));
    assertEquals("feature_flag", ChangeTracker.normalizeKind(" Feature_Flag "));
    assertEquals("other", ChangeTracker.normalizeKind("deploy"));
    assertEquals("other", ChangeTracker.normalizeKind(""));
    assertEquals("other", ChangeTracker.normalizeKind(null));
    for (String kind : ChangeTracker.KINDS) {
      assertEquals(kind, ChangeTracker.normalizeKind(kind));
    }
  }

  @Test
  void aBlankTitleIsDroppedAndALongOneIsTruncated() {
    assertNull(ChangeTracker.changePayload(Change.of("config", "  "), enabledConfiguration()));
    assertNull(ChangeTracker.changePayload(Change.of("config", null), enabledConfiguration()));

    Map<String, Object> payload =
        ChangeTracker.changePayload(Change.of("config", "x".repeat(250)), enabledConfiguration());
    assertEquals(200, ((String) payload.get("title")).length());
  }

  @Test
  void recordChangeDeliversToTheChangesEndpoint() throws InterruptedException {
    init(config -> config.setDetectChanges(false));

    ForgeOpsTracker.recordChange("deploy", "Something happened", Map.of("n", 1));

    String body = changes.poll(5, TimeUnit.SECONDS);
    assertNotNull(body, "change was not delivered in time");
    assertTrue(body.startsWith("{\"kind\":\"other\",\"title\":\"Something happened\",\"details\":{\"n\":1},"
        + "\"environment\":\"production\",\"occurred_at\":\""), body);
  }

  @Test
  void recordChangeNeverThrowsOnAServerErrorOrAPlanWithoutTheFeature() throws InterruptedException {
    status = 403;
    init(config -> config.setDetectChanges(false));

    assertDoesNotThrow(() -> ForgeOpsTracker.recordChange(Change.of("config", "Rejected")));
    assertNotNull(changes.poll(5, TimeUnit.SECONDS));

    status = 500;
    assertDoesNotThrow(() -> ForgeOpsTracker.recordChange(Change.of("config", "Rejected again")));
    assertNotNull(changes.poll(5, TimeUnit.SECONDS));
  }

  @Test
  void recordChangeNeverThrowsWhenNothingIsListening() throws IOException {
    int closedPort;
    try (ServerSocket probe = new ServerSocket(0)) {
      closedPort = probe.getLocalPort();
    }
    ForgeOpsTracker.init(config -> {
      config.setDsn("http://key@localhost:" + closedPort + "/api/v1/events");
      config.setEnvironment("production");
      config.setConnectTimeout(Duration.ofMillis(300));
    }, false);

    assertDoesNotThrow(() -> ForgeOpsTracker.recordChange("config", "Nobody home"));
    assertDoesNotThrow(() -> ForgeOpsTracker.recordChange((Change) null));
  }

  @Test
  void recordChangeIsANoOpWhenTheClientIsNotEnabled() throws InterruptedException {
    init(config -> config.setEnvironment("development"));

    ForgeOpsTracker.recordChange("config", "Not sent");

    assertNull(changes.poll(300, TimeUnit.MILLISECONDS));
    assertNull(snapshots.poll(10, TimeUnit.MILLISECONDS));
  }

  @Test
  void snapshotCarriesTheRuntimeAndNothingItCannotKnow() {
    Map<String, Object> payload = ChangeTracker.snapshotPayload(enabledConfiguration(), Map.of("DATABASE_URL", "x"));

    assertEquals("production", payload.get("environment"));
    assertFalse(payload.containsKey("service"));
    @SuppressWarnings("unchecked")
    Map<String, Object> state = (Map<String, Object>) payload.get("state");
    assertEquals(Map.of("runtime", "java " + System.getProperty("java.version")), state);
  }

  @Test
  void snapshotListsEnvVarNamesOnlyWhenOptedInAndNeverValues() {
    Configuration config = enabledConfiguration();
    config.setTrackEnvVarNames(true);
    Map<String, String> env = new LinkedHashMap<>();
    env.put("STRIPE_SECRET_KEY", "sk_live_do_not_send");
    env.put("DATABASE_URL", "postgres://secret");
    env.put("HOSTNAME", "web-1");

    Map<String, Object> payload = ChangeTracker.snapshotPayload(config, env);

    @SuppressWarnings("unchecked")
    Map<String, Object> state = (Map<String, Object>) payload.get("state");
    assertEquals(List.of("DATABASE_URL", "STRIPE_SECRET_KEY"), state.get("env_var_names"));
    String json = Json.encode(payload);
    assertFalse(json.contains("sk_live_do_not_send"));
    assertFalse(json.contains("postgres://secret"));
  }

  @Test
  void theDenylistDropsHostSpecificNoise() {
    for (String name : List.of("HOSTNAME", "HOST", "HOME", "PATH", "PWD", "OLDPWD", "SHLVL", "_", "TERM", "USER",
        "LOGNAME", "SHELL", "LANG", "LC_ALL", "LC_CTYPE", "TMPDIR", "TZ", "PORT", "DYNO", "INVOCATION_ID",
        "JOURNAL_STREAM", "SYSTEMD_EXEC_PID", "MEMORY_PRESSURE_WATCH", "KUBERNETES_SERVICE_HOST",
        "REDIS_SERVICE_HOST", "REDIS_SERVICE_PORT", "REDIS_SERVICE_PORT_HTTP", "REDIS_PORT_6379_TCP",
        "REDIS_PORT_6379_TCP_ADDR", "FORGE_OPS_DSN", "FORGE_OPS_ENVIRONMENT", "Path")) {
      assertTrue(ChangeTracker.isNoiseEnvVar(name), name);
    }
    for (String name : List.of("DATABASE_URL", "STRIPE_SECRET_KEY", "FEATURE_NEW_CHECKOUT", "SERVICE_NAME",
        "REDIS_PORT", "HOSTS_FILE")) {
      assertFalse(ChangeTracker.isNoiseEnvVar(name), name);
    }
  }

  @Test
  void initSendsOneSnapshotPerProcess() throws InterruptedException {
    init(config -> {});

    String body = snapshots.poll(5, TimeUnit.SECONDS);
    assertNotNull(body, "snapshot was not delivered in time");
    assertEquals("{\"environment\":\"production\",\"state\":{\"runtime\":\"java "
        + System.getProperty("java.version") + "\"}}", body);

    init(config -> {});
    assertNull(snapshots.poll(500, TimeUnit.MILLISECONDS));
  }

  @Test
  void initSendsRealEnvVarNamesWithTheDenylistAppliedWhenOptedIn() throws InterruptedException {
    init(config -> config.setTrackEnvVarNames(true));

    String body = snapshots.poll(5, TimeUnit.SECONDS);
    assertNotNull(body, "snapshot was not delivered in time");
    assertTrue(body.contains("\"env_var_names\":["), body);
    assertFalse(body.contains("\"PATH\""), body);
    assertFalse(body.contains("\"dependencies\""), body);
    String path = System.getenv("PATH");
    if (path != null && !path.isEmpty()) {
      assertFalse(body.contains(path), "an environment variable value was sent");
    }
  }

  @Test
  void detectChangesFalseSendsNoSnapshot() throws InterruptedException {
    init(config -> config.setDetectChanges(false));

    assertNull(snapshots.poll(500, TimeUnit.MILLISECONDS));
  }

  @Test
  void theChangeUrisSwapTheTrailingEventsSegment() {
    Configuration config = enabledConfiguration();
    assertEquals("https://tracker.example.com/api/v1/changes", config.getChangesUri().toString());
    assertEquals("https://tracker.example.com/api/v1/change_snapshots", config.getChangeSnapshotsUri().toString());
  }

  @Test
  void detectChangesDefaultsOnAndTrackEnvVarNamesDefaultsOff() {
    Configuration config = new Configuration();
    assertTrue(config.isDetectChanges());
    assertFalse(config.isTrackEnvVarNames());
  }
}
