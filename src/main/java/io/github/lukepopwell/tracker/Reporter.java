package io.github.lukepopwell.tracker;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates one capture: enabled check, build, enqueue. An error reporter that itself throws
 * while reporting an error is the worst possible failure mode, so every path here is wrapped to
 * guarantee this never propagates an exception back into the caller: the same guarantee the
 * Ruby/.NET/Python/PHP/Node clients each make in their own equivalent class.
 */
public class Reporter {
  private final Configuration configuration;
  private final EventBuilder eventBuilder;
  private final DeliveryQueue deliveryQueue;

  public Reporter(Configuration configuration) {
    this(configuration, new EventBuilder(configuration), new DeliveryQueue(configuration));
  }

  Reporter(Configuration configuration, EventBuilder eventBuilder, DeliveryQueue deliveryQueue) {
    this.configuration = configuration;
    this.eventBuilder = eventBuilder;
    this.deliveryQueue = deliveryQueue;
  }

  public void report(Throwable error, Map<String, Object> context) {
    report(error, context, null, List.of());
  }

  public void report(Throwable error, Map<String, Object> context, Map<String, Object> user) {
    report(error, context, user, List.of());
  }

  public void report(
      Throwable error, Map<String, Object> context, Map<String, Object> user, List<Map<String, Object>> breadcrumbs) {
    report(error, context, user, breadcrumbs, null, null, null);
  }

  /** Same, plus where the error happened (see {@link EventBuilder}'s seven-argument {@code build}). */
  public void report(Throwable error, Map<String, Object> context, Map<String, Object> user,
      List<Map<String, Object>> breadcrumbs, String traceId, String transactionName, String endpoint) {
    try {
      if (!configuration.isEnabled() || error == null) {
        return;
      }
      deliveryQueue.push(eventBuilder.build(error, context, user, breadcrumbs, traceId, transactionName, endpoint));
    } catch (RuntimeException e) {
      configuration.log("report failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** Sends what is still queued within {@code timeout}; see {@link DeliveryQueue#drain}. */
  public boolean flush(Duration timeout) {
    return deliveryQueue.drain(timeout);
  }

  /**
   * Builds and enqueues one {@code /changes} payload; same enabled check and never-throw guarantee
   * as {@link #report}.
   */
  public void recordChange(Change change) {
    try {
      if (!configuration.isEnabled()) {
        return;
      }
      Map<String, Object> payload = ChangeTracker.changePayload(change, configuration);
      if (payload == null) {
        configuration.log("recordChange ignored: a title is required");
        return;
      }
      deliveryQueue.pushChange(payload);
    } catch (RuntimeException e) {
      configuration.log("recordChange failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /**
   * Builds and enqueues the startup change snapshot from {@code env}; a no-op (returning false)
   * when the client isn't enabled or {@code detectChanges} is off.
   */
  boolean sendChangeSnapshot(Map<String, String> env) {
    try {
      if (!configuration.isEnabled() || !configuration.isDetectChanges()) {
        return false;
      }
      return deliveryQueue.pushChangeSnapshot(ChangeTracker.snapshotPayload(configuration, env));
    } catch (RuntimeException e) {
      configuration.log("change snapshot failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
      return false;
    }
  }
}
