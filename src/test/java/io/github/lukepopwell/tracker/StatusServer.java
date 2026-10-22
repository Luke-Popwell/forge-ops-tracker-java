package io.github.lukepopwell.tracker;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A real local endpoint that answers every request with whatever status it was last told to, and
 * keeps each body it was sent: what the flusher tests use to show how a batch is treated after a
 * 4xx or a 5xx, with the real {@link Client} in between rather than a stand-in for it.
 */
final class StatusServer implements AutoCloseable {
  final List<String> bodies = new CopyOnWriteArrayList<>();
  private final HttpServer server;
  private volatile int status;

  StatusServer(String path, int status) throws IOException {
    this.status = status;
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(path, exchange -> {
      bodies.add(new String(exchange.getRequestBody().readAllBytes()));
      exchange.sendResponseHeaders(this.status, -1);
      exchange.close();
    });
    server.start();
  }

  void respondWith(int newStatus) {
    status = newStatus;
  }

  /** A configuration whose DSN points at this server, logging to {@code log}. */
  Configuration configuration(List<String> log) {
    Configuration config = new Configuration();
    config.setDsn("http://key@localhost:" + server.getAddress().getPort() + "/api/v1/events");
    config.setEnvironment("production");
    config.setRelease("1.2.3");
    config.setLogger(log::add);
    return config;
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
