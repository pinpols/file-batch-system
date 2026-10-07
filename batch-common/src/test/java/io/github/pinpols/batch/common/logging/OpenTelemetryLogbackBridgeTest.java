package io.github.pinpols.batch.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@DisplayName("日志回传桥接:根日志器上追加器的装配与卸载,以及日志正文与上下文属性的导出")
class OpenTelemetryLogbackBridgeTest {

  @Test
  @DisplayName("桥接对象创建时挂载追加器,关闭后从根日志器卸载")
  void shouldAttachThenDetachAppender_whenBridgeClosed() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    try (OpenTelemetryLogbackBridge bridge = new OpenTelemetryLogbackBridge(OpenTelemetry.noop())) {
      assertThat(root.getAppender(OpenTelemetryLogbackBridge.APPENDER_NAME)).isNotNull();
    }

    assertThat(root.getAppender(OpenTelemetryLogbackBridge.APPENDER_NAME)).isNull();
  }

  @Test
  @DisplayName("桥接启用期间输出日志:正文与上下文属性一并导出到远端")
  void shouldExportLogBodyAndContextAttributes() {
    CapturingExporter exporter = new CapturingExporter();
    try (SdkLoggerProvider provider = SdkLoggerProvider.builder()
        .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
        .build()) {
      OpenTelemetry telemetry =
          OpenTelemetrySdk.builder().setLoggerProvider(provider).build();
      BatchMdc.put(StructuredLogField.TENANT_ID, "tenant-a");

      try (OpenTelemetryLogbackBridge ignored = new OpenTelemetryLogbackBridge(telemetry)) {
        LoggerFactory.getLogger("otel-bridge-test").info("otel-bridge-probe");
      } finally {
        BatchMdc.clear();
      }
    }

    assertThat(exporter.records).anySatisfy(record -> {
      assertThat(record.getBodyValue().asString()).isEqualTo("otel-bridge-probe");
      assertThat(record.getAttributes().get(AttributeKey.stringKey("tenantId")))
          .isEqualTo("tenant-a");
    });
  }

  private static final class CapturingExporter implements LogRecordExporter {

    private final List<LogRecordData> records = new CopyOnWriteArrayList<>();

    @Override
    public CompletableResultCode export(Collection<LogRecordData> logs) {
      records.addAll(logs);
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }
  }
}
