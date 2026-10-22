package io.github.lukepopwell.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link Client} against a real local HTTP server ({@code com.sun.net.httpserver},
 * built into the JDK) rather than a mocking framework: no extra test dependency needed, and it
 * confirms the actual bytes/headers this client sends, the same "test against something real"
 * approach the PHP client's {@code php -S} fixture servers use.
 */
class ClientTest {
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void deliversSuccessfullyOnA2xxResponse() throws IOException {
    AtomicReference<String> receivedAuth = new AtomicReference<>();
    AtomicReference<String> receivedBody = new AtomicReference<>();
    server = startServer((exchange, body) -> {
      receivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
      receivedBody.set(body);
      exchange.sendResponseHeaders(202, -1);
    });

    Configuration config = new Configuration();
    config.setDsn("http://the-api-key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
    Client client = new Client(config);

    boolean delivered = client.deliver(Map.of("message", "boom"));

    assertTrue(delivered);
    assertEquals("Bearer the-api-key", receivedAuth.get());
    assertEquals("{\"message\":\"boom\"}", receivedBody.get());
  }

  @Test
  void returnsFalseOnANon2xxResponse() throws IOException {
    server = startServer((exchange, body) -> exchange.sendResponseHeaders(500, -1));

    Configuration config = new Configuration();
    config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
    Client client = new Client(config);

    assertFalse(client.deliver(Map.of("message", "boom")));
  }

  @Test
  void returnsFalseWhenThereIsNoIngestionUri() {
    Configuration config = new Configuration();
    config.setDsn(null);
    Client client = new Client(config);

    assertFalse(client.deliver(Map.of("message", "boom")));
  }

  @Test
  void returnsFalseRatherThanThrowingWhenNothingIsListening() throws IOException {
    int closedPort;
    try (ServerSocket probe = new ServerSocket(0)) {
      closedPort = probe.getLocalPort();
    }

    Configuration config = new Configuration();
    config.setDsn("http://key@localhost:" + closedPort + "/api/v1/events");
    config.setConnectTimeout(Duration.ofMillis(300));
    Client client = new Client(config);

    assertFalse(client.deliver(Map.of("message", "boom")));
  }

  @Test
  void deliverPerformanceSamplesPostsTheBatchWrappedInSamples() throws IOException {
    AtomicReference<String> receivedPath = new AtomicReference<>();
    AtomicReference<String> receivedBody = new AtomicReference<>();
    server = startServer("/api/v1/performance_samples", (exchange, body) -> {
      receivedPath.set(exchange.getRequestURI().getPath());
      receivedBody.set(body);
      exchange.sendResponseHeaders(202, -1);
    });

    Configuration config = new Configuration();
    config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
    Client client = new Client(config);

    boolean delivered = client.deliverPerformanceSamples(List.of(Map.of("transaction_name", "GET /posts/{id}")));

    assertTrue(delivered);
    assertEquals("/api/v1/performance_samples", receivedPath.get());
    assertEquals("{\"samples\":[{\"transaction_name\":\"GET /posts/{id}\"}]}", receivedBody.get());
  }

  @Test
  void outcomeTellsARejectedPayloadApartFromOneThatOnlyFailedForNow() throws IOException {
    AtomicInteger status = new AtomicInteger();
    server = startServer((exchange, body) -> exchange.sendResponseHeaders(status.get(), -1));

    Configuration config = new Configuration();
    config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
    Client client = new Client(config);

    Map<Integer, DeliveryResult> expected = Map.ofEntries(
        Map.entry(202, DeliveryResult.DELIVERED),
        Map.entry(400, DeliveryResult.REJECTED),
        Map.entry(401, DeliveryResult.REJECTED),
        Map.entry(403, DeliveryResult.REJECTED),
        Map.entry(413, DeliveryResult.REJECTED),
        Map.entry(422, DeliveryResult.REJECTED),
        Map.entry(408, DeliveryResult.FAILED),
        Map.entry(429, DeliveryResult.FAILED),
        Map.entry(500, DeliveryResult.FAILED),
        Map.entry(503, DeliveryResult.FAILED));
    expected.forEach((code, result) -> {
      status.set(code);
      assertEquals(result, client.outcome(() -> client.deliver(Map.of("message", "boom"))), "HTTP " + code);
    });
  }

  @Test
  void outcomeIsAFailureForNowWhenNothingIsListening() throws IOException {
    int closedPort;
    try (ServerSocket probe = new ServerSocket(0)) {
      closedPort = probe.getLocalPort();
    }

    Configuration config = new Configuration();
    config.setDsn("http://key@localhost:" + closedPort + "/api/v1/events");
    config.setConnectTimeout(Duration.ofMillis(300));
    Client client = new Client(config);

    assertEquals(DeliveryResult.FAILED, client.outcome(() -> client.deliver(Map.of("message", "boom"))));
  }

  private interface Handler {
    void handle(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException;
  }

  private HttpServer startServer(Handler handler) throws IOException {
    return startServer("/api/v1/events", handler);
  }

  private HttpServer startServer(String path, Handler handler) throws IOException {
    HttpServer httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    httpServer.createContext(path, exchange -> {
      String body = new String(exchange.getRequestBody().readAllBytes());
      handler.handle(exchange, body);
      exchange.close();
    });
    httpServer.start();
    return httpServer;
  }
}
