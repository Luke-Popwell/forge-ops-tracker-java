package io.github.lukepopwell.tracker;

/**
 * Buckets a single duration into one of a fixed set of latency-range labels, the building block
 * {@link PerformanceFlusher} uses to accumulate an approximate distribution (not just
 * count/sum/max) alongside every transaction bucket it already tallies. The server merges these
 * counts across matching samples at read time and walks cumulative counts to approximate a
 * percentile, accurate to the bucket width: this SDK never stores the raw duration list a true
 * percentile would need. Ported from
 * {@code gems/forge_ops_tracker/lib/forge_ops_tracker/histogram_bucketer.rb}.
 *
 * <p>{@link #BOUNDARIES_MS} is duplicated on the server side, in
 * {@code app/services/histogram_percentile.rb}. Change one, change the other, or a released SDK
 * version and the server it talks to would silently disagree about what each bucket label means.
 */
final class HistogramBucketer {
  static final long[] BOUNDARIES_MS = {50, 100, 250, 500, 1000, 2500, 5000, 10_000};

  private HistogramBucketer() {}

  /**
   * Returns the label of the smallest boundary {@code durationMs} fits under, or {@code "inf"}
   * for anything larger than the largest boundary. A String, not a number: this travels as a
   * JSON object key once flushed, and JSON object keys are always strings.
   */
  static String bucketFor(double durationMs) {
    for (long boundary : BOUNDARIES_MS) {
      if (durationMs <= boundary) {
        return Long.toString(boundary);
      }
    }
    return "inf";
  }
}
