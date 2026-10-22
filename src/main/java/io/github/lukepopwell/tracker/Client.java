package io.github.lukepopwell.tracker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Delivers one payload over HTTP via {@link HttpClient} (built into the JDK since 11: no HTTP
 * library dependency needed, same reasoning as {@code net/http} in the Ruby gem, {@code urllib}/
 * {@code requests} in Python, and native {@code fetch} in Node). Every failure mode: DNS,
 * connection, timeout, TLS, a non-2xx response: is caught here and turned into a {@code false}
 * return rather than a thrown exception, since a broken or unreachable tracker must never be able
 * to break the host app.
 */
public class Client {
  private final Configuration configuration;
  private final HttpClient httpClient;
  /** Set by {@link #post} when the server rejected what this thread just sent; see {@link #outcome}. */
  private final ThreadLocal<Boolean> rejected = new ThreadLocal<>();

  public Client(Configuration configuration) {
    this(configuration, HttpClient.newBuilder()
        .connectTimeout(configuration.getConnectTimeout())
        .build());
  }

  Client(Configuration configuration, HttpClient httpClient) {
    this.configuration = configuration;
    this.httpClient = httpClient;
  }

  public boolean deliver(Map<String, Object> payload) {
    return post(configuration.getIngestionUri(), payload);
  }

  public boolean deliverSessionCheckin(Map<String, Object> payload) {
    return post(configuration.getSessionCheckinsUri(), payload);
  }

  public boolean deliverPerformanceSamples(List<Map<String, Object>> samples) {
    return post(configuration.getPerformanceSamplesUri(), Map.<String, Object>of("samples", samples));
  }

  public boolean deliverMetrics(List<Map<String, Object>> entries) {
    return post(configuration.getCustomMetricsUri(), Map.<String, Object>of("metrics", entries));
  }

  public boolean deliverInfrastructureMetrics(List<Map<String, Object>> entries) {
    return post(configuration.getInfrastructureMetricsUri(), Map.<String, Object>of("metrics", entries));
  }

  public boolean deliverSpans(Map<String, Object> trace) {
    return post(configuration.getSpansUri(), trace);
  }

  public boolean deliverChange(Map<String, Object> change) {
    return post(configuration.getChangesUri(), change);
  }

  public boolean deliverChangeSnapshot(Map<String, Object> snapshot) {
    return post(configuration.getChangeSnapshotsUri(), snapshot);
  }

  /**
   * Runs one of the {@code deliver} methods above and says how it ended, telling a payload the
   * server rejected apart from one that only failed for now. Goes through the public boolean
   * method rather than around it, so a subclass that overrides one still decides the outcome: its
   * {@code false} is a failure worth retrying unless the request it made was rejected.
   */
  DeliveryResult outcome(BooleanSupplier delivery) {
    rejected.remove();
    try {
      if (delivery.getAsBoolean()) {
        return DeliveryResult.DELIVERED;
      }
      return rejected.get() != null ? DeliveryResult.REJECTED : DeliveryResult.FAILED;
    } finally {
      rejected.remove();
    }
  }

  private boolean post(URI uri, Map<String, Object> payload) {
    if (uri == null) {
      return false;
    }
    try {
      HttpRequest request = HttpRequest.newBuilder(uri)
          .header("Authorization", "Bearer " + configuration.getApiKey())
          .header("Content-Type", "application/json")
          .timeout(configuration.getReadTimeout())
          .POST(HttpRequest.BodyPublishers.ofString(Json.encode(payload)))
          .build();
      HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
      DeliveryResult result = DeliveryResult.ofStatus(response.statusCode());
      if (result == DeliveryResult.REJECTED) {
        rejected.set(Boolean.TRUE);
        configuration.log("delivery rejected: HTTP " + response.statusCode());
      } else if (result == DeliveryResult.FAILED) {
        configuration.log("delivery failed: HTTP " + response.statusCode());
      }
      return result == DeliveryResult.DELIVERED;
    } catch (IOException | InterruptedException | RuntimeException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      configuration.log("delivery failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
      return false;
    }
  }
}
