package io.github.pinpols.batch.sdk.handler.typed;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A.2 typed Dispatch 模板基类单测 — 验证强类型入参 + 逐条推送 + 单条失败计 failed 不中断。 */
class SdkAbstractTypedDispatchHandlerTest {

  record DispatchRequest(int count) {}

  static final class RecordingTypedDispatch
      extends SdkAbstractTypedDispatchHandler<DispatchRequest, Void, Integer> {
    final List<Integer> pushed = new ArrayList<>();

    @Override
    public String taskType() {
      return "tenant_typed_dispatch";
    }

    @Override
    protected List<Integer> selectPayload(DispatchRequest input, SdkTaskContext ctx) {
      return IntStream.range(0, input.count()).boxed().toList();
    }

    @Override
    protected Object buildRequest(DispatchRequest input, SdkTaskContext ctx, Integer item) {
      return "req-" + item;
    }

    /** 偶数 item 推送成功,奇数抛异常 → 计 failed。 */
    @Override
    protected Object push(DispatchRequest input, SdkTaskContext ctx, Object request) {
      String r = (String) request;
      int item = Integer.parseInt(r.substring("req-".length()));
      if (item % 2 != 0) {
        throw new IllegalStateException("push failed for " + item);
      }
      pushed.add(item);
      return "ok";
    }
  }

  private static SdkTaskContext ctxWith(Map<String, Object> params) {
    return new SdkTaskContext("t1", "job", "ti", 1L, "w1", params, Map.of());
  }

  @Test
  @DisplayName("强类型入参 → 逐条推送,奇数失败计 failed 不中断整批")
  void shouldDispatchAndCountFailures_whenSomeItemsFail() {
    // 准备:6 条 → 0,2,4 成功(3),1,3,5 失败(3)
    RecordingTypedDispatch handler = new RecordingTypedDispatch();

    // 执行
    SdkTaskResult result = handler.execute(ctxWith(Map.of("count", 6)));

    // 断言
    assertThat(result.success()).isTrue();
    assertThat(handler.pushed).containsExactly(0, 2, 4);
    assertThat(result.output()).containsEntry("success", 3L).containsEntry("failed", 3L);
  }

  @Test
  @DisplayName("参数不匹配 → fail")
  void shouldFail_whenParametersInvalid() {
    // 准备
    RecordingTypedDispatch handler = new RecordingTypedDispatch();

    // 执行
    SdkTaskResult result = handler.execute(ctxWith(Map.of("count", "x")));

    // 断言
    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("invalid parameters");
  }
}
