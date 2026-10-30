package io.github.pinpols.batch.e2e.support;

import io.github.pinpols.batch.e2e.apps.E2eOrchestratorApplication;
import io.github.pinpols.batch.e2e.apps.E2eSettlementProcessConfiguration;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.BatchDaySettleScheduler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** 日历的自动补扫控制状态的 HTTP 表示。 */
public final class BatchDaySettlementChildProcess {

  private BatchDaySettlementChildProcess() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      throw new IllegalArgumentException("expected the settlement process properties path");
    }
    Properties properties = new Properties();
    try (var input = Files.newInputStream(Path.of(args[0]))) {
      properties.load(input);
    }
    String[] applicationArgs = properties.stringPropertyNames().stream()
        .map(key -> "--" + key + "=" + properties.getProperty(key))
        .toArray(String[]::new);
    try (ConfigurableApplicationContext application = new SpringApplicationBuilder(
            E2eOrchestratorApplication.class, E2eSettlementProcessConfiguration.class)
        .profiles("test")
        .web(WebApplicationType.NONE)
        .run(applicationArgs)) {
      Path readyPath = Path.of(properties.getProperty("batch.r11.ready-file"));
      Path releasePath = Path.of(properties.getProperty("batch.r11.release-file"));
      Files.writeString(readyPath, "ready");
      Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
      while (!Files.exists(releasePath) && Instant.now().isBefore(deadline)) {
        Thread.sleep(50);
      }
      if (!Files.exists(releasePath)) {
        throw new IllegalStateException("timed out waiting for the R11 settlement release signal");
      }
      application.getBean(BatchDaySettleScheduler.class).settle();
    }
  }
}
