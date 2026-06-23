package io.github.pinpols.batch.sdk.handler.typed;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.sdk.handler.SdkRowResult;
import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A.2 typed Export 模板基类单测 — 验证强类型入参 + 行流写出模板。 */
class SdkAbstractTypedExportHandlerTest {

  record ExportRequest(String table, int rows) {}

  static class RecordingTypedExport
      extends SdkAbstractTypedExportHandler<ExportRequest, Void, Integer> {
    final List<Integer> written = new ArrayList<>();
    String querySeen;
    int closeCalls;

    @Override
    public String taskType() {
      return "tenant_typed_export";
    }

    @Override
    protected String buildQuery(ExportRequest input, SdkTaskContext ctx) {
      return "SELECT * FROM " + input.table();
    }

    @Override
    protected Stream<Integer> streamRows(ExportRequest input, SdkTaskContext ctx, String query) {
      this.querySeen = query;
      return IntStream.range(0, input.rows()).boxed().onClose(() -> closeCalls++);
    }

    @Override
    protected void formatRow(ExportRequest input, SdkTaskContext ctx, Integer row) {
      written.add(row);
    }
  }

  private static SdkTaskContext ctxWith(Map<String, Object> params) {
    return new SdkTaskContext("t1", "job", "ti", 1L, "w1", params, Map.of());
  }

  @Test
  @DisplayName("强类型入参 → buildQuery 用其字段,逐行写出,计数器 output 回退")
  void shouldExportRows_whenInputParsed() {
    // 准备
    RecordingTypedExport handler = new RecordingTypedExport();

    // 执行
    SdkTaskResult result = handler.execute(ctxWith(Map.of("table", "orders", "rows", 5)));

    // 断言
    assertThat(result.success()).isTrue();
    assertThat(handler.querySeen).isEqualTo("SELECT * FROM orders");
    assertThat(handler.written).hasSize(5);
    assertThat(result.output()).containsEntry("success", 5L);
    assertThat(handler.closeCalls).isEqualTo(1);
  }

  @Test
  @DisplayName("writeOut 返显式结果 → 覆盖默认 output")
  void shouldUseExplicitWriteOutResult_whenProvided() {
    // 准备
    RecordingTypedExport handler =
        new RecordingTypedExport() {
          @Override
          protected SdkTaskResult writeOut(
              ExportRequest input, SdkTaskContext ctx, SdkRowResult counts) {
            return SdkTaskResult.ok("uploaded", Map.of("uri", "s3://bucket/" + input.table()));
          }
        };

    // 执行
    SdkTaskResult result = handler.execute(ctxWith(Map.of("table", "orders", "rows", 3)));

    // 断言
    assertThat(result.success()).isTrue();
    assertThat(result.message()).isEqualTo("uploaded");
    assertThat(result.output()).containsEntry("uri", "s3://bucket/orders");
  }

  @Test
  @DisplayName("参数不匹配 → fail")
  void shouldFail_whenParametersInvalid() {
    // 准备
    RecordingTypedExport handler = new RecordingTypedExport();

    // 执行
    SdkTaskResult result = handler.execute(ctxWith(Map.of("rows", "x")));

    // 断言
    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("invalid parameters");
  }
}
