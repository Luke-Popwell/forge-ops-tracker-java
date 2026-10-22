package io.github.lukepopwell.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatabaseSpanTest {
  private HttpServer server;
  private final CountDownLatch received = new CountDownLatch(1);
  private final AtomicReference<String> body = new AtomicReference<>();

  @BeforeEach
  void start() throws Exception {
    ForgeOpsTracker.resetForTesting();
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/api/v1/spans", exchange -> {
      body.set(new String(exchange.getRequestBody().readAllBytes()));
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      received.countDown();
    });
    server.start();
    ForgeOpsTracker.init(config -> {
      config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
      config.setEnvironment("production");
      config.setTrackTracing(true);
      config.setTraceCaptureThreshold(Duration.ofMillis(10));
    }, false);
  }

  @AfterEach
  void stop() {
    ForgeOpsTracker.resetForTesting();
    server.stop(0);
  }

  private String deliver() throws Exception {
    ForgeOpsTracker.finishTrace("GET /orders", Instant.now(), 250);
    assertTrue(received.await(3, TimeUnit.SECONDS), "trace was never delivered");
    return body.get();
  }

  @Test
  void aDatabaseSpanSendsItsStatementMaskedWithItsSystemLowercased() throws Exception {
    ForgeOpsTracker.beginTrace();
    List<String> rows = ForgeOpsTracker.databaseSpan("Load orders",
        "SELECT * FROM orders WHERE email = 'jane@example.com' AND total > 4200", "PostgreSQL", () -> List.of("row"));
    assertEquals(List.of("row"), rows);

    String json = deliver();
    assertTrue(json.contains("\"kind\":\"database\""), json);
    assertTrue(json.contains("\"db.statement\":\"SELECT * FROM orders WHERE email = ? AND total > ?\""), json);
    assertTrue(json.contains("\"db.system\":\"postgresql\""), json);
    assertFalse(json.contains("jane@example.com"), json);
    assertFalse(json.contains("4200"), json);
  }

  @Test
  void recordDatabaseSpanMasksTooAndLeavesOutAMissingSystem() throws Exception {
    ForgeOpsTracker.beginTrace();
    ForgeOpsTracker.recordDatabaseSpan("Load user", "SELECT name FROM users WHERE id = 9911", null, Instant.now(), 3);

    String json = deliver();
    assertTrue(json.contains("\"db.statement\":\"SELECT name FROM users WHERE id = ?\""), json);
    assertFalse(json.contains("db.system"), json);
    assertFalse(json.contains("9911"), json);
  }

  @Test
  void aStatementPutInDataByHandIsMaskedAndTheCallersMapIsUntouched() throws Exception {
    ForgeOpsTracker.beginTrace();
    Map<String, Object> data = new HashMap<>();
    data.put("db.statement", "DELETE FROM carts WHERE token = 'abc123'");
    data.put("rows", 3);
    ForgeOpsTracker.recordSpan("Clear cart", "database", Instant.now(), 2, data);

    String json = deliver();
    assertTrue(json.contains("\"db.statement\":\"DELETE FROM carts WHERE token = ?\""), json);
    assertTrue(json.contains("\"rows\":3"), json);
    assertFalse(json.contains("abc123"), json);
    assertEquals("DELETE FROM carts WHERE token = 'abc123'", data.get("db.statement"));
  }

  @Test
  void aLongStatementIsTruncated() {
    String statement = "SELECT " + "column_name, ".repeat(500) + "id FROM orders";
    Map<String, Object> masked = SpanBuffer.maskDatabaseData(ForgeOpsTracker.databaseSpanData(statement, "mysql"));
    String value = (String) masked.get("db.statement");
    assertEquals(4003, value.length());
    assertTrue(value.endsWith("..."));
    assertEquals("mysql", masked.get("db.system"));
  }

  @Test
  void doubleQuotedStringsAreMaskedOnlyForMysqlAndMariadb() {
    String statement = "SELECT id FROM users WHERE token = \"abc123secret\"";
    assertEquals("SELECT id FROM users WHERE token = ?",
        SpanBuffer.maskDatabaseData(ForgeOpsTracker.databaseSpanData(statement, "MySQL")).get("db.statement"));
    assertEquals("SELECT id FROM users WHERE token = ?",
        SpanBuffer.maskDatabaseData(Map.of("db.statement", statement, "db.system", "mariadb")).get("db.statement"));
    assertEquals(statement,
        SpanBuffer.maskDatabaseData(ForgeOpsTracker.databaseSpanData(statement, "postgresql")).get("db.statement"));
  }

  @Test
  void aStatementThatIsNotAStringIsDropped() {
    Map<String, Object> masked = SpanBuffer.maskDatabaseData(Map.of("db.statement", 42, "db.system", "sqlite"));
    assertEquals(Map.of("db.system", "sqlite"), masked);
  }

  @Test
  void databaseSpanDataLeavesOutBlankValues() {
    assertEquals(Map.of(), ForgeOpsTracker.databaseSpanData("  ", null));
    assertEquals(Map.of("db.statement", "SELECT 1", "db.system", "sqlite"),
        ForgeOpsTracker.databaseSpanData("SELECT 1", " SQLite "));
  }

  @Test
  void outsideATraceADatabaseSpanJustRunsTheWork() throws Exception {
    assertEquals(7, (int) ForgeOpsTracker.databaseSpan("Load orders", "SELECT 1", "postgresql", () -> 7));
  }
}
