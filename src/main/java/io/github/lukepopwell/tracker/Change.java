package io.github.lukepopwell.tracker;

import java.time.Instant;
import java.util.Map;

/**
 * One explicit change for {@link ForgeOpsTracker#recordChange(Change)}: a feature flag flipped, a
 * config value edited, a migration run. Java has no keyword arguments, so the optional fields are
 * fluent setters instead of the long cascade of overloads {@link ForgeOpsTracker#addBreadcrumb}
 * uses for its three:
 *
 * <pre>{@code
 * ForgeOpsTracker.recordChange(Change.of("feature_flag", "Enabled new checkout")
 *     .details(Map.of("flag", "new_checkout", "rollout", 25))
 *     .actor("luke@example.com"));
 * }</pre>
 *
 * <p>Anything left unset is left out of the payload, except {@code environment} (the configured
 * environment) and {@code occurred_at} (now).
 */
public final class Change {
  private final String kind;
  private final String title;
  private Map<String, Object> details;
  private String environment;
  private String service;
  private String actor;
  private String url;
  private String id;
  private Instant occurredAt;

  private Change(String kind, String title) {
    this.kind = kind;
    this.title = title;
  }

  /**
   * {@code kind} is one of {@code feature_flag}, {@code config}, {@code migration}, {@code
   * dependency}, {@code infrastructure} or {@code other}; anything else is sent as {@code other}.
   */
  public static Change of(String kind, String title) {
    return new Change(kind, title);
  }

  /** A small JSON-friendly map ({@code String}/{@code Number}/{@code Boolean}, nested maps and lists). */
  public Change details(Map<String, Object> details) {
    this.details = details;
    return this;
  }

  public Change environment(String environment) {
    this.environment = environment;
    return this;
  }

  public Change service(String service) {
    this.service = service;
    return this;
  }

  public Change actor(String actor) {
    this.actor = actor;
    return this;
  }

  /** An http(s) link to more detail: a pull request, a deploy log, an admin page. */
  public Change url(String url) {
    this.url = url;
    return this;
  }

  /** An idempotency key: sending the same one twice records the change once. */
  public Change id(String id) {
    this.id = id;
    return this;
  }

  public Change occurredAt(Instant occurredAt) {
    this.occurredAt = occurredAt;
    return this;
  }

  String kind() {
    return kind;
  }

  String title() {
    return title;
  }

  Map<String, Object> details() {
    return details;
  }

  String environment() {
    return environment;
  }

  String service() {
    return service;
  }

  String actor() {
    return actor;
  }

  String url() {
    return url;
  }

  String id() {
    return id;
  }

  Instant occurredAt() {
    return occurredAt;
  }
}
