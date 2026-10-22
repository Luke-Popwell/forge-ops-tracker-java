package io.github.lukepopwell.tracker;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A bounded, in-order trail of what happened right before an error: the automatic controller
 * breadcrumb {@code ForgeOpsTrackerPerformanceTrackingFilter} records (the only automatic source
 * this client has, see that class's own doc comment for why), plus anything added by hand via
 * {@link ForgeOpsTracker#addBreadcrumb}. Ported from
 * {@code gems/forge_ops_tracker/lib/forge_ops_tracker/breadcrumb_buffer.rb}: a ring buffer capped
 * at {@link Configuration#getMaxBreadcrumbs()}, oldest entry dropped once full.
 *
 * <p>Held one-per-thread via {@code ForgeOpsTracker}'s own {@code ThreadLocal<BreadcrumbBuffer>},
 * the same isolation boundary {@code currentUser} already uses there (see that field's own
 * Javadoc for why a servlet container's one-thread-per-request model makes this the correct
 * choice for Java, the same reasoning the Ruby gem's own {@code Thread.current}-based buffer
 * documents): so unlike {@link PerformanceFlusher}/{@link SessionFlusher} (one shared instance,
 * mutated concurrently from every request's own thread, hence their own {@code synchronized}
 * blocks), a single {@code BreadcrumbBuffer} instance is only ever touched by the one thread that
 * owns it, so no synchronization is needed here.
 */
public class BreadcrumbBuffer {
  private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_INSTANT;

  private final Configuration configuration;
  private final List<Map<String, Object>> entries = new ArrayList<>();

  public BreadcrumbBuffer(Configuration configuration) {
    this.configuration = configuration;
  }

  public void add(String category, String message, String level, Map<String, Object> data) {
    // Read fresh on every add, not captured once at construction: a config change made through
    // ForgeOpsTracker.configuration() should take effect on whatever's added next, not just a
    // buffer created afterward, the same reasoning the Ruby gem's own BreadcrumbBuffer#add
    // documents for its own re-read of Configuration#max_breadcrumbs.
    // Strict privacy never sends breadcrumbs, so none are kept either: a message is free text.
    int maxSize = Math.max(configuration.getMaxBreadcrumbs(), 0);
    if (maxSize == 0 || configuration.isStrictPrivacy()) {
      return;
    }

    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("category", orEmpty(category));
    entry.put("message", orEmpty(message));
    entry.put("level", orEmpty(level));
    entry.put("timestamp", TIMESTAMP_FORMAT.format(Instant.now()));
    entry.put("data", data == null ? Map.of() : data);
    entries.add(entry);

    while (entries.size() > maxSize) {
      entries.remove(0);
    }
  }

  /**
   * A copy, not the internal list: the same "never hand out the live buffer" contract the Ruby
   * gem's own {@code #all} documents.
   */
  public List<Map<String, Object>> all() {
    return new ArrayList<>(entries);
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }
}
