package io.github.lukepopwell.tracker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * The child JVM {@link ExitFlushTest} launches: reports one error the way a short CLI program does,
 * then ends the way {@code args[0]} names. Delivery goes through a slow fake client that appends
 * each message to the file {@code args[1]}, so an event the JVM exits on before delivering never
 * reaches the file.
 */
public final class ExitFlushMain {
  private ExitFlushMain() {}

  public static void main(String[] args) throws Exception {
    String mode = args[0];
    Path out = Path.of(args[1]);
    if (mode.equals("setup-page")) {
      // Exactly what the setup page shows: FORGE_OPS_DSN in the environment (set by ExitFlushTest)
      // and a bare init(). Called twice to show the "not sending" warning still prints only once.
      ForgeOpsTracker.init(config -> config.setDetectChanges(false));
      ForgeOpsTracker.init(config -> config.setDetectChanges(false));
    } else {
      ForgeOpsTracker.init(config -> {
        config.setDsn("https://key@example.com/api/v1/events");
        config.setEnvironment("production");
        config.setDetectChanges(false);
      });
    }
    Configuration config = ForgeOpsTracker.configuration();
    Field reporter = ForgeOpsTracker.class.getDeclaredField("reporter");
    reporter.setAccessible(true);
    reporter.set(null,
        new Reporter(config, new EventBuilder(config), new DeliveryQueue(config, new FileClient(config, out))));

    switch (mode) {
      case "setup-page" -> ForgeOpsTracker.captureException(new RuntimeException("first test error"));
      case "return" -> ForgeOpsTracker.captureException(new RuntimeException("reported just before exit"));
      case "exit" -> {
        ForgeOpsTracker.captureException(new RuntimeException("reported before System.exit"));
        System.exit(3);
      }
      case "uncaught" -> throw new IllegalStateException("crashed main");
      case "flush-then-halt" -> {
        ForgeOpsTracker.captureException(new RuntimeException("flushed before halt"));
        ForgeOpsTracker.flush();
        Runtime.getRuntime().halt(0);
      }
      case "halt" -> {
        ForgeOpsTracker.captureException(new RuntimeException("lost to halt"));
        Runtime.getRuntime().halt(0);
      }
      default -> throw new IllegalArgumentException(mode);
    }
  }

  /** Slow enough that a JVM exiting right after the report always outruns the worker thread. */
  private static final class FileClient extends Client {
    private final Path out;

    FileClient(Configuration configuration, Path out) {
      super(configuration);
      this.out = out;
    }

    @Override
    public boolean deliver(Map<String, Object> payload) {
      try {
        Thread.sleep(300);
        Files.writeString(out, payload.get("message") + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return true;
    }
  }
}
