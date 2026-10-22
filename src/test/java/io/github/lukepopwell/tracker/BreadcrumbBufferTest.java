package io.github.lukepopwell.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BreadcrumbBufferTest {
  @Test
  void returnsEntriesInInsertionOrderEachCarryingTheGivenFieldsPlusATimestamp() {
    Configuration config = new Configuration();
    config.setMaxBreadcrumbs(3);
    BreadcrumbBuffer buffer = new BreadcrumbBuffer(config);

    buffer.add("query", "User Load", "info", Map.of("duration_ms", 1.2));

    Map<String, Object> entry = buffer.all().get(0);
    assertEquals("query", entry.get("category"));
    assertEquals("User Load", entry.get("message"));
    assertEquals("info", entry.get("level"));
    assertEquals(Map.of("duration_ms", 1.2), entry.get("data"));
    assertTrue(entry.get("timestamp").toString().endsWith("Z"));
  }

  @Test
  void defaultsDataToAnEmptyMapWhenNull() {
    Configuration config = new Configuration();
    BreadcrumbBuffer buffer = new BreadcrumbBuffer(config);

    buffer.add("custom", "checkpoint", "info", null);

    assertEquals(Map.of(), buffer.all().get(0).get("data"));
  }

  @Test
  void dropsTheOldestEntryOnceMaxBreadcrumbsIsExceeded() {
    Configuration config = new Configuration();
    config.setMaxBreadcrumbs(3);
    BreadcrumbBuffer buffer = new BreadcrumbBuffer(config);

    for (int n = 0; n < 4; n++) {
      buffer.add("custom", "entry " + n, "info", Map.of());
    }

    List<Object> messages = buffer.all().stream().map(entry -> entry.get("message")).map(Object.class::cast).toList();
    assertEquals(List.of("entry 1", "entry 2", "entry 3"), messages);
  }

  @Test
  void reReadsMaxBreadcrumbsOnEveryAddNotJustAtConstruction() {
    Configuration config = new Configuration();
    config.setMaxBreadcrumbs(2);
    BreadcrumbBuffer buffer = new BreadcrumbBuffer(config);

    buffer.add("custom", "one", "info", Map.of());
    buffer.add("custom", "two", "info", Map.of());
    config.setMaxBreadcrumbs(1);
    buffer.add("custom", "three", "info", Map.of());

    List<Object> messages = buffer.all().stream().map(entry -> entry.get("message")).map(Object.class::cast).toList();
    assertEquals(List.of("three"), messages);
  }

  @Test
  void addsNothingAtAllWhenMaxBreadcrumbsIsZero() {
    Configuration config = new Configuration();
    config.setMaxBreadcrumbs(0);
    BreadcrumbBuffer buffer = new BreadcrumbBuffer(config);

    buffer.add("custom", "one", "info", Map.of());

    assertEquals(List.of(), buffer.all());
  }

  @Test
  void addsNothingWhenMaxBreadcrumbsIsNegative() {
    Configuration config = new Configuration();
    config.setMaxBreadcrumbs(-5);
    BreadcrumbBuffer buffer = new BreadcrumbBuffer(config);

    buffer.add("custom", "one", "info", Map.of());

    assertEquals(List.of(), buffer.all());
  }

  @Test
  void allReturnsACopyNotTheInternalList() {
    Configuration config = new Configuration();
    BreadcrumbBuffer buffer = new BreadcrumbBuffer(config);
    buffer.add("custom", "one", "info", Map.of());

    List<Map<String, Object>> snapshot = buffer.all();
    snapshot.add(Map.of("category", "custom", "message", "mutated externally"));

    assertEquals(1, buffer.all().size());
  }
}
