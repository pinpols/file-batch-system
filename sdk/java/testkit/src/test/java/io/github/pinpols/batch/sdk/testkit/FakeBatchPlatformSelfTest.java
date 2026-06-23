package io.github.pinpols.batch.sdk.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.sdk.client.BatchPlatformClient;
import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskHandler;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * testkit 自测 —— 同时是 roadmap §7.4 DoD「租户用 testkit 写 handler 集成测,5s 跑完」的可执行证明。
 *
 * <p>跑通的链路:{@code client.start()} → SDK consumer 订到内嵌 broker → {@link FakeBatchPlatform#dispatch}
 * 真发派单 → handler 执行 → REPORT 回到 HTTP stub → {@link FakeBatchPlatform#awaitReport} 拿到结果。
 */
@BatchWorkerTest
class FakeBatchPlatformSelfTest {

  /** 成功路径:handler 返回 ok + outputs,平台收到 success=true 的 report。 */
  @Test
  @DisplayName("handler 成功执行,5s 内收到成功 report")
  void handlerSucceeds_endToEnd(FakeBatchPlatform platform) {
    SdkTaskHandler handler =
        new SdkTaskHandler() {
          @Override
          public String taskType() {
            return "testkit_demo";
          }

          @Override
          public SdkTaskResult execute(SdkTaskContext ctx) {
            int rows = ((Number) ctx.parameters().getOrDefault("rows", 0)).intValue();
            return SdkTaskResult.ok("imported " + rows, Map.of("rows", rows));
          }
        };

    BatchPlatformClient client =
        BatchPlatformClient.builder(platform.configFor("tenant-a", "worker-a"))
            .register(handler)
            .build();
    client.start();
    try {
      platform.dispatch(
          TaskDispatchMessageBuilder.dispatch("testkit_demo")
              .tenantId("tenant-a")
              .taskId(101L)
              .param("rows", 3)
              .build());

      RecordedReport report = platform.awaitReport(101L, Duration.ofSeconds(5));
      assertThat(report.success()).isTrue();
      assertThat(report.message()).isEqualTo("imported 3");
      assertThat(report.outputs()).containsEntry("rows", 3);
      assertThat(platform.claims()).contains(101L);
      assertThat(platform.registrations()).isNotEmpty();
    } finally {
      client.stop();
    }
  }

  /** 失败路径:handler 抛异常,平台收到 success=false + errorCode 的 report。 */
  @Test
  @DisplayName("handler 抛异常,report 标记失败并带 errorCode")
  void handlerThrows_reportsFailure(FakeBatchPlatform platform) {
    SdkTaskHandler handler =
        new SdkTaskHandler() {
          @Override
          public String taskType() {
            return "testkit_boom";
          }

          @Override
          public SdkTaskResult execute(SdkTaskContext ctx) {
            throw new IllegalStateException("kaboom");
          }
        };

    BatchPlatformClient client =
        BatchPlatformClient.builder(platform.configFor("tenant-a", "worker-boom"))
            .register(handler)
            .build();
    client.start();
    try {
      platform.dispatch(
          TaskDispatchMessageBuilder.dispatch("testkit_boom")
              .tenantId("tenant-a")
              .taskId(202L)
              .build());

      RecordedReport report = platform.awaitReport(202L, Duration.ofSeconds(5));
      assertThat(report.success()).isFalse();
      assertThat(report.errorCode()).isEqualTo("IllegalStateException");
      assertThat(report.message()).contains("kaboom");
    } finally {
      client.stop();
    }
  }
}
