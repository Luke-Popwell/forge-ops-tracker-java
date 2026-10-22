package io.github.lukepopwell.tracker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReporterTest {
  @Test
  void buildsAndPushesWhenEnabled() {
    Configuration config = enabledConfig();
    RecordingDeliveryQueue queue = new RecordingDeliveryQueue(config);
    Reporter reporter = new Reporter(config, new EventBuilder(config), queue);

    reporter.report(new RuntimeException("boom"), Map.of("order_id", 1));

    assertEquals(1, queue.pushed.size());
    assertEquals("java.lang.RuntimeException", queue.pushed.get(0).get("exception_class"));
  }

  @Test
  void passesTheUserThroughToTheEventBuilder() {
    Configuration config = enabledConfig();
    RecordingDeliveryQueue queue = new RecordingDeliveryQueue(config);
    Reporter reporter = new Reporter(config, new EventBuilder(config), queue);

    reporter.report(new RuntimeException("boom"), Map.of(), Map.of("id", 42, "email", "alice@example.com"));

    assertEquals(1, queue.pushed.size());
    assertEquals(Map.of("id", 42, "email", "alice@example.com"), queue.pushed.get(0).get("user"));
  }

  @Test
  void passesTheBreadcrumbsThroughToTheEventBuilder() {
    Configuration config = enabledConfig();
    RecordingDeliveryQueue queue = new RecordingDeliveryQueue(config);
    Reporter reporter = new Reporter(config, new EventBuilder(config), queue);
    List<Map<String, Object>> breadcrumbs =
        List.of(Map.of("category", "controller", "message", "GET /orders", "level", "info", "data", Map.of()));

    reporter.report(new RuntimeException("boom"), Map.of(), Map.of(), breadcrumbs);

    assertEquals(1, queue.pushed.size());
    assertEquals(breadcrumbs, queue.pushed.get(0).get("breadcrumbs"));
  }

  @Test
  void omitsBreadcrumbsWhenTheThreeArgumentOverloadIsUsed() {
    Configuration config = enabledConfig();
    RecordingDeliveryQueue queue = new RecordingDeliveryQueue(config);
    Reporter reporter = new Reporter(config, new EventBuilder(config), queue);

    reporter.report(new RuntimeException("boom"), Map.of(), Map.of("id", 1));

    assertFalse(queue.pushed.get(0).containsKey("breadcrumbs"));
  }

  @Test
  void doesNothingWhenDisabled() {
    Configuration config = new Configuration();
    config.setDsn(null); // disabled: no DSN
    RecordingDeliveryQueue queue = new RecordingDeliveryQueue(config);
    Reporter reporter = new Reporter(config, new EventBuilder(config), queue);

    reporter.report(new RuntimeException("boom"), Map.of());

    assertTrue(queue.pushed.isEmpty());
  }

  @Test
  void neverThrowsEvenIfEventBuildingFails() {
    Configuration config = enabledConfig();
    RecordingDeliveryQueue queue = new RecordingDeliveryQueue(config);
    Reporter reporter = new Reporter(config, new ExplodingEventBuilder(config), queue);

    assertDoesNotThrow(() -> reporter.report(new RuntimeException("boom"), Map.of()));
    assertTrue(queue.pushed.isEmpty());
  }

  @Test
  void neverThrowsEvenIfANullErrorIsPassed() {
    Configuration config = enabledConfig();
    RecordingDeliveryQueue queue = new RecordingDeliveryQueue(config);
    Reporter reporter = new Reporter(config, new EventBuilder(config), queue);

    assertDoesNotThrow(() -> reporter.report(null, Map.of()));
    assertFalse(queue.pushed.size() > 0);
  }

  private Configuration enabledConfig() {
    Configuration config = new Configuration();
    config.setDsn("https://key@example.com/api/v1/events");
    config.setEnvironment("production");
    return config;
  }

  private static class RecordingDeliveryQueue extends DeliveryQueue {
    final List<Map<String, Object>> pushed = new ArrayList<>();

    RecordingDeliveryQueue(Configuration configuration) {
      super(configuration, new Client(configuration));
    }

    @Override
    public boolean push(Map<String, Object> payload) {
      pushed.add(payload);
      return true;
    }
  }

  private static class ExplodingEventBuilder extends EventBuilder {
    ExplodingEventBuilder(Configuration configuration) {
      super(configuration);
    }

    @Override
    public Map<String, Object> build(Throwable error, Map<String, Object> context) {
      throw new IllegalStateException("event building blew up");
    }
  }
}
