package io.github.pinpols.batch.common.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.LoggerFactory;

/** 将 Logback 事件桥接到 Spring Boot 管理的 OpenTelemetry LoggerProvider。 */
public final class OpenTelemetryLogbackBridge implements AutoCloseable {

  static final String APPENDER_NAME = "OTEL";

  private final Logger rootLogger;
  private final OpenTelemetryAppender appender;

  public OpenTelemetryLogbackBridge(OpenTelemetry openTelemetry) {
    if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext loggerContext)) {
      rootLogger = null;
      appender = null;
      return;
    }
    rootLogger = loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    OpenTelemetryAppender otelAppender = new OpenTelemetryAppender();
    otelAppender.setName(APPENDER_NAME);
    otelAppender.setContext(loggerContext);
    otelAppender.setCaptureMdcAttributes("*");
    otelAppender.setCaptureKeyValuePairAttributes(true);
    otelAppender.setOpenTelemetry(openTelemetry);
    otelAppender.start();
    rootLogger.addAppender(otelAppender);
    appender = otelAppender;
  }

  @Override
  public void close() {
    if (rootLogger == null || appender == null) {
      return;
    }
    rootLogger.detachAppender(appender);
    appender.stop();
  }
}
