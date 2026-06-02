package com.example.batch.sdk.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.example.batch.sdk.internal.PlatformHttpClient;
import com.example.batch.sdk.task.SdkTaskHandler;
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

    // arrange: 构造一条 tenantId != config.tenantId 的合法消息
    TaskDispatchMessage foreign =
        new TaskDispatchMessage(
            42L, "tenant-b", "job-1", "X", "ti-1", Map.of(), Map.of("traceId", "t-1"));

    // act
    dispatcher.onMessage(foreign);

    // assert: 既不 claim 也不调 handler.execute
    verify(http, never()).claim(anyLong(), anyString(), any());
    verify(handler, never()).execute(any());
    // 状态机不受污染
    assertThat(dispatcher.isFatal()).isFalse();
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
