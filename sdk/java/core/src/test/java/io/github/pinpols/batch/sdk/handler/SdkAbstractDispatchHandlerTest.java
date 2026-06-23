package io.github.pinpols.batch.sdk.handler;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-036 Dispatch 模板单测 — 经基类 execute 模板序,验证单条失败不中断整批。 */
class SdkAbstractDispatchHandlerTest {

  private static SdkTaskContext ctx() {
    return new SdkTaskContext("tx", "job", "ti", 1L, "w-1", Map.of(), Map.of());
  }

  @Test
  @DisplayName("5 条全成功 → success=5 failed=0 total=5 整体成功")
  void shouldDispatchAll_whenNoFailure() {
    // 准备
    var handler =
        new SdkAbstractDispatchHandler<Integer>() {
          @Override
          public String taskType() {
            return "test_dispatch";
          }

          @Override
          protected List<Integer> selectPayload(SdkTaskContext c) {
            return List.of(1, 2, 3, 4, 5);
          }

          @Override
          protected Object buildRequest(SdkTaskContext c, Integer item) {
            return "req-" + item;
          }

          @Override
          protected Object push(SdkTaskContext c, Object request) {
            return "resp-" + request;
          }
        };

    // 执行
    SdkTaskResult result = handler.execute(ctx());

    // 断言
    assertThat(result.success()).isTrue();
    assertThat(result.message()).isEqualTo("dispatched 5/5");
    // SdkRowResult.toOutput() 零计数项不写(保持 output 精简),failed=0 时不出现
    assertThat(result.output())
        .containsEntry("success", 5L)
        .containsEntry("total", 5L)
        .doesNotContainKey("failed");
  }

  @Test
  @DisplayName("5 条其中 2 条 push 抛异常 → success=3 failed=2,整体仍成功")
  void shouldContinueBatch_whenSomePushFails() {
    var handler =
        new SdkAbstractDispatchHandler<Integer>() {
          @Override
          public String taskType() {
            return "test_dispatch";
          }

          @Override
          protected List<Integer> selectPayload(SdkTaskContext c) {
            return List.of(1, 2, 3, 4, 5);
          }

          @Override
          protected Object buildRequest(SdkTaskContext c, Integer item) {
            return item;
          }

          @Override
          protected Object push(SdkTaskContext c, Object request) {
            int v = (int) request;
            if (v == 2 || v == 4) {
              throw new IllegalStateException("push failed for " + v);
            }
            return "resp-" + v;
          }
        };

    SdkTaskResult result = handler.execute(ctx());

    assertThat(result.success()).isTrue();
    assertThat(result.message()).isEqualTo("dispatched 3/5");
    assertThat(result.output())
        .containsEntry("success", 3L)
        .containsEntry("failed", 2L)
        .containsEntry("total", 5L);
  }

  @Test
  @DisplayName("空 payload 列表 → success=0,整体成功")
  void shouldSucceedWithZero_whenEmptyPayload() {
    var handler =
        new SdkAbstractDispatchHandler<Integer>() {
          @Override
          public String taskType() {
            return "test_dispatch";
          }

          @Override
          protected List<Integer> selectPayload(SdkTaskContext c) {
            return List.of();
          }

          @Override
          protected Object buildRequest(SdkTaskContext c, Integer item) {
            return item;
          }

          @Override
          protected Object push(SdkTaskContext c, Object request) {
            return request;
          }
        };

    SdkTaskResult result = handler.execute(ctx());

    assertThat(result.success()).isTrue();
    assertThat(result.message()).isEqualTo("dispatched 0/0");
    assertThat(result.output()).containsEntry("success", 0L).containsEntry("total", 0L);
  }

  @Test
  @DisplayName("某条 buildRequest 抛异常 → 该条 failed,其它继续")
  void shouldContinueBatch_whenBuildRequestFails() {
    var handler =
        new SdkAbstractDispatchHandler<Integer>() {
          @Override
          public String taskType() {
            return "test_dispatch";
          }

          @Override
          protected List<Integer> selectPayload(SdkTaskContext c) {
            return List.of(1, 2, 3);
          }

          @Override
          protected Object buildRequest(SdkTaskContext c, Integer item) {
            if (item == 2) {
              throw new IllegalArgumentException("bad item " + item);
            }
            return item;
          }

          @Override
          protected Object push(SdkTaskContext c, Object request) {
            return "resp-" + request;
          }
        };

    SdkTaskResult result = handler.execute(ctx());

    assertThat(result.success()).isTrue();
    assertThat(result.output())
        .containsEntry("success", 2L)
        .containsEntry("failed", 1L)
        .containsEntry("total", 3L);
  }

  @Test
  @DisplayName("某条 onResponse 抛异常 → 该条 failed,其它继续")
  void shouldContinueBatch_whenOnResponseFails() {
    var handler =
        new SdkAbstractDispatchHandler<Integer>() {
          @Override
          public String taskType() {
            return "test_dispatch";
          }

          @Override
          protected List<Integer> selectPayload(SdkTaskContext c) {
            return List.of(1, 2, 3);
          }

          @Override
          protected Object buildRequest(SdkTaskContext c, Integer item) {
            return item;
          }

          @Override
          protected Object push(SdkTaskContext c, Object request) {
            return "resp-" + request;
          }

          @Override
          protected void onResponse(SdkTaskContext c, Integer item, Object response) {
            if (item == 3) {
              throw new IllegalStateException("onResponse failed " + item);
            }
          }
        };

    SdkTaskResult result = handler.execute(ctx());

    assertThat(result.success()).isTrue();
    assertThat(result.output())
        .containsEntry("success", 2L)
        .containsEntry("failed", 1L)
        .containsEntry("total", 3L);
  }

  @Test
  @DisplayName("selectPayload 抛异常 → execute 整体失败")
  void shouldFailWhole_whenSelectPayloadThrows() {
    var handler =
        new SdkAbstractDispatchHandler<Integer>() {
          @Override
          public String taskType() {
            return "test_dispatch";
          }

          @Override
          protected List<Integer> selectPayload(SdkTaskContext c) {
            throw new IllegalStateException("db down");
          }

          @Override
          protected Object buildRequest(SdkTaskContext c, Integer item) {
            return item;
          }

          @Override
          protected Object push(SdkTaskContext c, Object request) {
            return request;
          }
        };

    SdkTaskResult result = handler.execute(ctx());

    assertThat(result.success()).isFalse();
    assertThat(result.message()).isEqualTo("db down");
  }

  @Test
  @DisplayName("onResponse 收到的 response == push 返回值(透传)")
  void shouldPassThroughResponse_fromPushToOnResponse() {
    var seenResponses = new ArrayList<Object>();
    var pushReturn = new Object();
    var handler =
        new SdkAbstractDispatchHandler<Integer>() {
          @Override
          public String taskType() {
            return "test_dispatch";
          }

          @Override
          protected List<Integer> selectPayload(SdkTaskContext c) {
            return List.of(1);
          }

          @Override
          protected Object buildRequest(SdkTaskContext c, Integer item) {
            return item;
          }

          @Override
          protected Object push(SdkTaskContext c, Object request) {
            return pushReturn;
          }

          @Override
          protected void onResponse(SdkTaskContext c, Integer item, Object response) {
            seenResponses.add(response);
          }
        };

    handler.execute(ctx());

    assertThat(seenResponses).hasSize(1);
    assertThat(seenResponses.get(0)).isSameAs(pushReturn);
  }

  @Test
  @DisplayName("push 收到的 request == buildRequest 返回值(透传)")
  void shouldPassThroughRequest_fromBuildRequestToPush() {
    var seenRequest = new AtomicReference<Object>();
    var builtRequest = new Object();
    var handler =
        new SdkAbstractDispatchHandler<Integer>() {
          @Override
          public String taskType() {
            return "test_dispatch";
          }

          @Override
          protected List<Integer> selectPayload(SdkTaskContext c) {
            return List.of(1);
          }

          @Override
          protected Object buildRequest(SdkTaskContext c, Integer item) {
            return builtRequest;
          }

          @Override
          protected Object push(SdkTaskContext c, Object request) {
            seenRequest.set(request);
            return "resp";
          }
        };

    handler.execute(ctx());

    assertThat(seenRequest.get()).isSameAs(builtRequest);
  }
}
