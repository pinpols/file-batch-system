package io.github.pinpols.batch.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class OpenTelemetryLogbackBridgeTest {

  @Test
  void attachesAndDetachesAppenderFromRootLogger() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    try (OpenTelemetryLogbackBridge bridge = new OpenTelemetryLogbackBridge(OpenTelemetry.noop())) {
      assertThat(root.getAppender(OpenTelemetryLogbackBridge.APPENDER_NAME)).isNotNull();
    }

    assertThat(root.getAppender(OpenTelemetryLogbackBridge.APPENDER_NAME)).isNull();
  }
}
