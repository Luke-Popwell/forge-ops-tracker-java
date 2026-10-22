package io.github.lukepopwell.tracker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HistogramBucketerTest {
  @Test
  void returnsTheSmallestBoundaryADurationFitsUnderAsAString() {
    assertEquals("50", HistogramBucketer.bucketFor(10));
    assertEquals("50", HistogramBucketer.bucketFor(50));
    assertEquals("100", HistogramBucketer.bucketFor(50.5));
    assertEquals("5000", HistogramBucketer.bucketFor(4999));
  }

  @Test
  void returnsInfForAnythingLargerThanTheLargestBoundary() {
    assertEquals("inf", HistogramBucketer.bucketFor(10_001));
    assertEquals("inf", HistogramBucketer.bucketFor(1_000_000));
  }

  @Test
  void putsADurationExactlyOnABoundaryIntoThatBoundarysOwnBucket() {
    for (long boundary : HistogramBucketer.BOUNDARIES_MS) {
      assertEquals(Long.toString(boundary), HistogramBucketer.bucketFor(boundary));
    }
  }

  @Test
  void boundariesMatchTheServersHistogramPercentile() {
    // app/services/histogram_percentile.rb and every other SDK must agree on this exact list.
    assertArrayEquals(new long[] {50, 100, 250, 500, 1000, 2500, 5000, 10_000}, HistogramBucketer.BOUNDARIES_MS);
  }
}
