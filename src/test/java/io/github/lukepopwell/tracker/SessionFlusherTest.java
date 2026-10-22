package io.github.lukepopwell.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SessionFlusherTest {
  private static Configuration newConfiguration() {
    Configuration config = new Configuration();
    config.setRelease("1.2.3");
    config.setEnvironment("production");
    return config;
  }

  @Test
  void aggregatesCountsAndDeliversASnapshotResettingAfterASuccessfulFlush() {
    List<Map<String, Object>> delivered = new CopyOnWriteArrayList<>();
    RecordingClient client = new RecordingClient(delivered);

    SessionFlusher flusher = new SessionFlusher(newConfiguration(), client);
    flusher.recordSession(false);
    flusher.recordSession(true);
    flusher.recordSession(false);
    flusher.flush();

    assertEquals(1, delivered.size());
    assertEquals(3L, delivered.get(0).get("sessions_count"));
    assertEquals(1L, delivered.get(0).get("crashed_sessions_count"));
    assertEquals("1.2.3", delivered.get(0).get("release"));
    assertEquals("production", delivered.get(0).get("environment"));

    // Nothing new was recorded since the successful flush above, so this one sends nothing.
    flusher.flush();
    assertEquals(1, delivered.size());

    flusher.shutdown();
  }

  @Test
  void doesNothingOnAFlushWithNoSessionsRecorded() {
    AtomicInteger callCount = new AtomicInteger();
    Client client = new Client(newConfiguration()) {
      @Override
      public boolean deliverSessionCheckin(Map<String, Object> payload) {
        callCount.incrementAndGet();
        return true;
      }
    };

    SessionFlusher flusher = new SessionFlusher(newConfiguration(), client);
    flusher.flush();

    assertEquals(0, callCount.get());
    flusher.shutdown();
  }

  @Test
  void keepsTheCountsForTheNextFlushInsteadOfLosingThemWhenDeliveryFails() {
    List<Map<String, Object>> delivered = new CopyOnWriteArrayList<>();
    AtomicBoolean shouldSucceed = new AtomicBoolean(false);
    Client client = new Client(newConfiguration()) {
      @Override
      public boolean deliverSessionCheckin(Map<String, Object> payload) {
        if (!shouldSucceed.get()) {
          return false;
        }
        delivered.add(payload);
        return true;
      }
    };

    SessionFlusher flusher = new SessionFlusher(newConfiguration(), client);
    flusher.recordSession(false);
    flusher.flush(); // fails; the count must not be reset

    shouldSucceed.set(true);
    flusher.recordSession(false);
    flusher.flush();

    assertEquals(2L, delivered.get(0).get("sessions_count"));
    flusher.shutdown();
  }

  @Test
  void dropsACheckinTheServerRejectsAndNeverSendsItAgain() throws IOException {
    List<String> log = new CopyOnWriteArrayList<>();
    try (StatusServer server = new StatusServer("/api/v1/session_checkins", 422)) {
      Configuration config = server.configuration(log);
      SessionFlusher flusher = new SessionFlusher(config, new Client(config));
      flusher.recordSession(false);
      flusher.recordSession(true);
      flusher.flush(); // rejected: the counts are let go
      flusher.flush(); // nothing left to send

      assertEquals(1, server.bodies.size());
      assertEquals(1, log.stream().filter(line -> line.contains("dropped a session checkin of 2 sessions")).count());

      server.respondWith(202);
      flusher.recordSession(false);
      flusher.flush();

      assertEquals(2, server.bodies.size());
      assertTrue(server.bodies.get(1).contains("\"sessions_count\":1"));
      assertTrue(server.bodies.get(1).contains("\"crashed_sessions_count\":0"));
      flusher.shutdown();
    }
  }

  @Test
  void keepsTheCountsWhenTheServerAnswersWithA5xx() throws IOException {
    List<String> log = new CopyOnWriteArrayList<>();
    try (StatusServer server = new StatusServer("/api/v1/session_checkins", 500)) {
      Configuration config = server.configuration(log);
      SessionFlusher flusher = new SessionFlusher(config, new Client(config));
      flusher.recordSession(false);
      flusher.flush(); // fails for now; the count stays

      server.respondWith(202);
      flusher.recordSession(false);
      flusher.flush();

      assertEquals(2, server.bodies.size());
      assertTrue(server.bodies.get(1).contains("\"sessions_count\":2"));
      flusher.shutdown();
    }
  }

  @Test
  void dropsTheCountsOnTheFifthFailedFlushInARow() {
    AtomicInteger attempts = new AtomicInteger();
    List<String> log = new CopyOnWriteArrayList<>();
    Configuration config = newConfiguration();
    config.setLogger(log::add);
    Client client = new Client(config) {
      @Override
      public boolean deliverSessionCheckin(Map<String, Object> payload) {
        attempts.incrementAndGet();
        return false;
      }
    };

    SessionFlusher flusher = new SessionFlusher(config, client);
    flusher.recordSession(false);
    for (int i = 0; i < 4; i++) {
      flusher.flush();
    }
    assertTrue(log.isEmpty()); // four failures in: still kept

    flusher.flush(); // the fifth: dropped
    flusher.flush(); // nothing left to send

    assertEquals(5, attempts.get());
    assertEquals(1, log.stream().filter(line -> line.contains("delivery failed 5 times in a row")).count());
    flusher.shutdown();
  }

  @Test
  void theScheduledExecutorFlushesOnItsOwnAfterTheConfiguredInterval() throws InterruptedException {
    CountDownLatch delivered = new CountDownLatch(1);
    List<Map<String, Object>> received = new CopyOnWriteArrayList<>();
    RecordingClient client = new RecordingClient(received, delivered);

    Configuration config = newConfiguration();
    config.setSessionFlushInterval(Duration.ofMillis(1)); // rounded up to 1s by getSessionFlushInterval's own floor

    SessionFlusher flusher = new SessionFlusher(config, client);
    flusher.recordSession(false);

    assertTrue(delivered.await(3, TimeUnit.SECONDS), "session checkin was not delivered in time");
    assertEquals(1L, received.get(0).get("sessions_count"));

    flusher.shutdown();
  }

  private static class RecordingClient extends Client {
    private final List<Map<String, Object>> received;
    private final CountDownLatch delivered;

    RecordingClient(List<Map<String, Object>> received) {
      this(received, null);
    }

    RecordingClient(List<Map<String, Object>> received, CountDownLatch delivered) {
      super(new Configuration());
      this.received = received;
      this.delivered = delivered;
    }

    @Override
    public boolean deliverSessionCheckin(Map<String, Object> payload) {
      received.add(payload);
      if (delivered != null) {
        delivered.countDown();
      }
      return true;
    }
  }
}
