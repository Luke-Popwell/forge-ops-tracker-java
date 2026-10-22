package io.github.lukepopwell.tracker;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds the two "what changed" payloads: one explicit change ({@code /changes}) and the startup
 * snapshot ({@code /change_snapshots}) the server diffs against the previous one.
 *
 * <p>The snapshot carries only what this client can know for certain. The runtime version comes
 * straight from the JVM. Dependencies are left out on purpose: a Java classpath has no reliable
 * record of which library versions it holds (a shaded or repackaged jar hides its own, and a jar
 * manifest's version is optional), and an omitted key is treated server-side as unknown, never as
 * "everything removed", so leaving it out is safe where guessing isn't. There's no schema version
 * either, for the same reason.
 */
final class ChangeTracker {
  static final Set<String> KINDS =
      Set.of("feature_flag", "config", "migration", "dependency", "infrastructure", "other");

  private static final int MAX_TITLE_LENGTH = 200;
  private static final int MAX_ENV_VAR_NAMES = 3000;
  private static final int MAX_ENV_VAR_NAME_LENGTH = 200;

  /** Variables that differ from host to host (or run to run): a steady fleet would look like it's changing. */
  private static final Set<String> NOISE_ENV_VARS = Set.of("HOSTNAME", "HOST", "HOME", "PATH", "PWD", "OLDPWD",
      "SHLVL", "_", "TERM", "USER", "LOGNAME", "SHELL", "LANG", "TMPDIR", "TZ", "PORT", "DYNO", "INVOCATION_ID",
      "JOURNAL_STREAM");
  private static final List<String> NOISE_ENV_VAR_PREFIXES =
      List.of("LC_", "SYSTEMD_", "MEMORY_PRESSURE_", "KUBERNETES_", "FORGE_OPS_");
  /**
   * Service discovery variables Kubernetes and Docker links inject: {@code *_SERVICE_HOST}, {@code
   * *_SERVICE_PORT*} and {@code *_PORT_*_TCP*}.
   */
  private static final Pattern NOISE_ENV_VAR_PATTERN = Pattern.compile("_SERVICE_HOST$|_SERVICE_PORT|_PORT_.*_TCP");

  private ChangeTracker() {}

  /** One of {@link #KINDS}, case-insensitively; anything else (including null) becomes {@code other}. */
  static String normalizeKind(String kind) {
    if (kind == null) {
      return "other";
    }
    String normalized = kind.strip().toLowerCase(Locale.ROOT);
    return KINDS.contains(normalized) ? normalized : "other";
  }

  /** The {@code /changes} body, or {@code null} when there's no title (the server requires one). */
  static Map<String, Object> changePayload(Change change, Configuration configuration) {
    if (change == null || change.title() == null || change.title().isBlank()) {
      return null;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("kind", normalizeKind(change.kind()));
    payload.put("title", truncate(change.title().strip(), MAX_TITLE_LENGTH));
    if (change.details() != null && !change.details().isEmpty()) {
      payload.put("details", change.details());
    }
    String environment = change.environment() != null ? change.environment() : configuration.getEnvironment();
    putIfPresent(payload, "environment", environment);
    putIfPresent(payload, "service", change.service());
    putIfPresent(payload, "actor", change.actor());
    putIfPresent(payload, "url", change.url());
    Instant occurredAt = change.occurredAt() != null ? change.occurredAt() : Instant.now();
    payload.put("occurred_at", DateTimeFormatter.ISO_INSTANT.format(occurredAt));
    putIfPresent(payload, "id", change.id());
    return payload;
  }

  /** The {@code /change_snapshots} body, built from {@code env} (the process environment, outside tests). */
  static Map<String, Object> snapshotPayload(Configuration configuration, Map<String, String> env) {
    Map<String, Object> state = new LinkedHashMap<>();
    putIfPresent(state, "runtime", runtime());
    if (configuration.isTrackEnvVarNames()) {
      state.put("env_var_names", envVarNames(env));
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    putIfPresent(payload, "environment", configuration.getEnvironment());
    payload.put("state", state);
    return payload;
  }

  /** e.g. {@code "java 21.0.2"}. */
  static String runtime() {
    String version = System.getProperty("java.version");
    return version == null || version.isBlank() ? null : "java " + version;
  }

  /** Sorted, so the same environment always produces the same list whatever order the OS hands it over in. */
  static List<String> envVarNames(Map<String, String> env) {
    List<String> names = new ArrayList<>();
    for (String name : env.keySet()) {
      if (name != null && !name.isEmpty() && !isNoiseEnvVar(name)) {
        names.add(truncate(name, MAX_ENV_VAR_NAME_LENGTH));
      }
    }
    Collections.sort(names);
    return names.size() > MAX_ENV_VAR_NAMES ? new ArrayList<>(names.subList(0, MAX_ENV_VAR_NAMES)) : names;
  }

  /** The one denylist every snapshot uses. Case-insensitive, since Windows environment names are. */
  static boolean isNoiseEnvVar(String name) {
    String upper = name.toUpperCase(Locale.ROOT);
    if (NOISE_ENV_VARS.contains(upper)) {
      return true;
    }
    for (String prefix : NOISE_ENV_VAR_PREFIXES) {
      if (upper.startsWith(prefix)) {
        return true;
      }
    }
    return NOISE_ENV_VAR_PATTERN.matcher(upper).find();
  }

  private static void putIfPresent(Map<String, Object> map, String key, String value) {
    if (value != null && !value.isBlank()) {
      map.put(key, value);
    }
  }

  private static String truncate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }
}
