package com.example.batch.sdk.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A.2 typed Process 模板基类单测 — 验证强类型入参 + transform/skip + 分批 upsert。 */
class SdkAbstractTypedProcessHandlerTest {

  record ProcessRequest(int rows, int modulo) {}

  record ProcessResult(long kept) {}

  static final class RecordingTypedProcess
      extends SdkAbstractTypedProcessHandler<ProcessRequest, Integer, String, ProcessResult> {
    final List<Integer> upsertSizes = new ArrayList<>();

    @Override
    public String taskType() {
      return "tenant_typed_process";
    }

    @Override
    protected int batchSize() {
      return 10;
    }

    @Override
    protected Stream<Integer> selectInput(ProcessRequest input, SdkTaskContext ctx) {
      return IntStream.range(0, input.rows()).boxed();
    }

    /** modulo 倍数的行 skip(返 null),其余转成字符串。 */
    @Override
    protected String transform(ProcessRequest input, SdkTaskContext ctx, Integer row) {
      return row % input.modulo() == 0 ? null : "v" + row;
    }

    @Override
    protected void upsert(ProcessRequest input, SdkTaskContext ctx, List<String> batch) {
      upsertSizes.add(batch.size());
    }

    @Override
    protected ProcessResult summarize(ProcessRequest input, SdkRowResult counts) {
      return new ProcessResult(counts.success());
    }
  }

  private static SdkTaskContext ctxWith(Map<String, Object> params) {
    return new SdkTaskContext("t1", "job", "ti", 1L, "w1", params, Map.of());
  }

  @Test
  @DisplayName("transform 返 null 的行 skip,其余分批 upsert,summarize 进 output")
  void shouldSkipNullRowsAndBatchUpsert() {
    // arrange:25 行,modulo=5 → 0,5,10,15,20 skip(5 行),success=20
    RecordingTypedProcess handler = new RecordingTypedProcess();

    // act
    SdkTaskResult result = handler.execute(ctxWith(Map.of("rows", 25, "modulo", 5)));

    // assert
    assertThat(result.success()).isTrue();
    assertThat(result.output()).containsEntry("kept", 20L);
    // 20 个 output 行,batchSize=10 → 2 批满 + 收尾(本例 20 整除 → 10,10)
    assertThat(handler.upsertSizes).containsExactly(10, 10);
  }

  @Test
  @DisplayName("参数不匹配 → fail")
  void shouldFail_whenParametersInvalid() {
    // arrange
    RecordingTypedProcess handler = new RecordingTypedProcess();

    // act
    SdkTaskResult result = handler.execute(ctxWith(Map.of("rows", "x", "modulo", 1)));

    // assert
    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("invalid parameters");
    assertThat(handler.upsertSizes).isEmpty();
  }
}
