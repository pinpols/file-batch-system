package io.github.pinpols.batch.sdk.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig;
import io.github.pinpols.batch.sdk.internal.PlatformHttpClient;
import io.github.pinpols.batch.sdk.task.SdkTaskHandler;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Lane J §J1:租户自检 fail-safe — Kafka ACL 漂移 / consumer group 配置失误导致拿到非本租户消息时, onMessage 在 fatal /
 * draining / 平台态 / validate 检查后,立即 ERROR log + drop, 不投递到 executor 也不调用 HTTP claim,本进程不串任务。
 */
class TaskDispatcherTenantMismatchTest {

  private final BatchPlatformClientConfig config =
      BatchPlatformClientConfig.builder()
          .baseUrl("http://localhost:0")
          .tenantId("tenant-a")
          .workerCode("w-1")
          .kafkaBootstrap("k:9092")
          .kafkaTopicPattern("p.*")
          .kafkaGroupId("g")
          .maxConcurrentTasks(2)
          .build();

  private TaskDispatcher dispatcher;

  @AfterEach
  void tearDown() {
    if (dispatcher != null) dispatcher.stop();
  }

  @Test
  void shouldDropMessage_whenTenantMismatch() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    SdkTaskHandler handler = mock(SdkTaskHandler.class);
    dispatcher = new TaskDispatcher(config, Map.of("X", handler), http);

    // 准备: 构造一条 tenantId != config.tenantId 的合法消息
    TaskDispatchMessage foreign =
        new TaskDispatchMessage(
            42L, "tenant-b", "job-1", "X", "ti-1", Map.of(), Map.of("traceId", "t-1"));

    // 执行
    TaskDispatcher.DispatchDecision decision = dispatcher.onMessage(foreign);

    // 断言: 既不 claim 也不调 handler.execute
    assertThat(decision).isEqualTo(TaskDispatcher.DispatchDecision.RETRY_LATER);
    verify(http, never()).claim(anyLong(), anyString(), any());
    verify(handler, never()).execute(any());
    // tenant mismatch 是 ACL / consumer group 漂移信号:进入 fatal,让 liveness/运维介入,offset 不前移。
    assertThat(dispatcher.isFatal()).isTrue();
    assertThat(dispatcher.isDraining()).isFalse();
  }

  @Test
  void shouldDispatchNormally_whenTenantMatches() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    SdkTaskHandler handler = mock(SdkTaskHandler.class);
    dispatcher = new TaskDispatcher(config, Map.of("X", handler), http);

    TaskDispatchMessage own =
        new TaskDispatchMessage(
            42L, "tenant-a", "job-1", "X", "ti-1", Map.of(), Map.of("traceId", "t-1"));

    dispatcher.onMessage(own);

    // 给 executor 异步任务一点点时间走到 claim
    Thread.sleep(200);
    verify(http).claim(anyLong(), anyString(), any());
  }
}
