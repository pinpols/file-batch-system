package io.github.pinpols.batch.orchestrator.integration;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.BatchDaySettleScheduler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Properties;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Separate JVM entry point used to exercise distributed settlement locking against PostgreSQL. */
public final class BatchDaySettleChildProcess {

  private BatchDaySettleChildProcess() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 4) {
      throw new IllegalArgumentException(
          "expected config path, tenant id, calendar code, and business date");
    }
    Properties properties = new Properties();
    try (var input = Files.newInputStream(Path.of(args[0]))) {
      properties.load(input);
    }
    String[] applicationArgs = properties.stringPropertyNames().stream()
        .map(key -> "--" + key + "=" + properties.getProperty(key))
        .toArray(String[]::new);
    try (ConfigurableApplicationContext application = new SpringApplicationBuilder(
            BatchOrchestratorApplication.class)
        .profiles("test")
        .web(WebApplicationType.NONE)
        .run(applicationArgs)) {
      application.getBean(BatchDaySettleScheduler.class).settle();
    }
  }
}
