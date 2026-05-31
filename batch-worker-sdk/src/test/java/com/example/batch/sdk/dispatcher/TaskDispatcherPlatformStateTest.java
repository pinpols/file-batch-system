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
import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** SDK Phase 2 §2.4:心跳指令驱动 4 态状态机 + onMessage 门控。 */
class TaskDispatcherPlatformStateTest {

  private final BatchPlatformClientConfig config =
      BatchPlatformClientConfig.builder()
          .baseUrl("http://localhost:0")
          .tenantId("tx")
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
  void defaultStateIsNormalAndAcceptsTasks() {
    dispatcher = new TaskDispatcher(config, Map.of(), mock(PlatformHttpClient.class));
    assertThat(dispatcher.platformState()).isEqualTo(WorkerRuntimeState.NORMAL);
    assertThat(dispatcher.platformAcceptsNewTasks()).isTrue();
  }

  @Test
  void directiveTransitionsState() {
    dispatcher = new TaskDispatcher(config, Map.of(), mock(PlatformHttpClient.class));

    dispatcher.applyPlatformDirective(
        new HeartbeatDirective("PAUSED", null, false, List.of(), null));
    assertThat(dispatcher.platformState()).isEqualTo(WorkerRuntimeState.PAUSED);
    assertThat(dispatcher.platformAcceptsNewTasks()).isFalse();

    dispatcher.applyPlatformDirective(
        new HeartbeatDirective("DRAINING", null, true, List.of(), null));
    assertThat(dispatcher.platformState()).isEqualTo(WorkerRuntimeState.DRAINING);

    // 恢复 NORMAL
    dispatcher.applyPlatformDirective(new HeartbeatDirective("NORMAL", 4, false, List.of(), null));
    assertThat(dispatcher.platformState()).isEqualTo(WorkerRuntimeState.NORMAL);
    assertThat(dispatcher.platformAcceptsNewTasks()).isTrue();
  }

  @Test
  void nullDirectiveIsNoop() {
    dispatcher = new TaskDispatcher(config, Map.of(), mock(PlatformHttpClient.class));
    dispatcher.applyPlatformDirective(null);
    assertThat(dispatcher.platformState()).isEqualTo(WorkerRuntimeState.NORMAL);
  }

  @Test
  void onMessageSkippedWhenPaused() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    AtomicReference<SdkTaskContext> seen = new AtomicReference<>();
    dispatcher =
        new TaskDispatcher(
            config,
            Map.of(
                "tt",
                new SdkTaskHandler() {
                  @Override
                  public String taskType() {
                    return "tt";
                  }

                  @Override
                  public SdkTaskResult execute(SdkTaskContext ctx) {
                    seen.set(ctx);
                    return SdkTaskResult.ok();
                  }
                }),
            http);

    dispatcher.applyPlatformDirective(
        new HeartbeatDirective("PAUSED", null, false, List.of(), null));
    dispatcher.onMessage(new TaskDispatchMessage(99L, "tx", "j", "tt", "ti", Map.of(), Map.of()));

    // PAUSED → 不 claim、不进 handler
    verify(http, never()).claim(anyLong(), anyString(), any());
    assertThat(seen.get()).isNull();
  }
}
