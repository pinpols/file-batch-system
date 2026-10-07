package io.github.pinpols.batch.sdk.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.sdk.dispatcher.KafkaTaskConsumer;
import io.github.pinpols.batch.sdk.dispatcher.TaskDispatcher;
import io.github.pinpols.batch.sdk.idempotent.Idempotent;
import io.github.pinpols.batch.sdk.internal.PlatformHttpClient;
import io.github.pinpols.batch.sdk.scheduler.HeartbeatScheduler;
import io.github.pinpols.batch.sdk.scheduler.LeaseRenewalScheduler;
import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskHandler;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;

/**
 * P7-3:验证 {@link BatchPlatformClient#start()} 的生命周期失败语义。
 *
 * <ul>
 *   <li>register API 抛 {@link IOException} → start() 抛 {@link RuntimeException}(message "worker
 *       register failed"),且<b>未</b>启动任何后台线程(dispatcher / kafkaConsumer 仍 null,started 仍
 *       false,isHealthy false)。
 *   <li>重复 start() → 抛 {@link IllegalStateException}。
 * </ul>
 *
 * <p>因 {@code httpClient} 字段在构造期 new 出且无注入入口,这里复用本测试包既有的反射注入 mock 套路(见 {@code
 * BatchPlatformClientStopOrderTest} / {@code BatchPlatformClientMetricsTest}),避免真拉 orchestrator。
 */
@DisplayName("BatchPlatformClient 启动生命周期 — 注册失败时回滚与重复启动防护")
class BatchPlatformClientStartTest {

  private static BatchPlatformClientConfig cfg() {
    return BatchPlatformClientConfig.builder()
        .baseUrl("https://batch.example.com")
        .tenantId("tx")
        .workerCode("w-1")
        .kafkaBootstrap("kafka:9092")
        .kafkaTopicPattern("batch.task.dispatch.tx.*")
        .kafkaGroupId("g")
        .build();
  }

  private static SdkTaskHandler stub(String type) {
    return new SdkTaskHandler() {
      @Override
      public String taskType() {
        return type;
      }

      @Override
      public SdkTaskResult execute(SdkTaskContext ctx) {
        return SdkTaskResult.ok();
      }
    };
  }

  @Idempotent(key = "startup:{taskInstanceId}")
  private static final class IdempotentHandlerWithoutStore implements SdkTaskHandler {

    @Override
    public String taskType() {
      return "idempotent-startup";
    }

    @Override
    public SdkTaskResult execute(SdkTaskContext ctx) {
      return SdkTaskResult.ok();
    }
  }

  private static void inject(BatchPlatformClient target, String field, Object value)
      throws Exception {
    Field f = BatchPlatformClient.class.getDeclaredField(field);
    f.setAccessible(true);
    f.set(target, value);
  }

  private static Object field(BatchPlatformClient target, String field) throws Exception {
    Field f = BatchPlatformClient.class.getDeclaredField(field);
    f.setAccessible(true);
    return f.get(target);
  }

  @Test
  @DisplayName("register 抛 IOException → start() 抛 RuntimeException,后台线程未启动、状态仍未就绪")
  void shouldThrowAndNotStartBackground_whenRegisterFails() throws Exception {
    // 准备
    BatchPlatformClient client =
        BatchPlatformClient.builder(cfg()).register(stub("type-a")).build();
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    ArgumentCaptor<Map<String, Object>> registerBody = ArgumentCaptor.captor();
    when(http.register(registerBody.capture()))
        .thenThrow(new IOException("orchestrator unreachable"));
    inject(client, "httpClient", http);

    // 执行并断言:抛 RuntimeException(register 失败让进程非 0 退出,K8s 重启)
    assertThatThrownBy(client::start)
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("worker register failed")
        .hasCauseInstanceOf(IOException.class);

    // 断言:无任何后台线程被装配,状态反映未就绪
    assertThat(field(client, "started")).isEqualTo(false);
    assertThat(field(client, "dispatcher")).isNull();
    assertThat(field(client, "kafkaConsumer")).isNull();
    assertThat(field(client, "kafkaConsumerThread")).isNull();
    assertThat(field(client, "heartbeatScheduler")).isNull();
    assertThat(field(client, "leaseRenewalScheduler")).isNull();
    assertThat(client.isHealthy()).isFalse();
    SdkClientMetrics m = client.metrics();
    assertThat(m.started()).isFalse();
    assertThat(m.healthy()).isFalse();
    assertThat(m.inFlightTaskCount()).isZero();
    assertThat(registerBody.getValue()).containsEntry("maxConcurrent", 4);
  }

  @Test
  @DisplayName("注册成功后运行组件构造失败 -> 强制注销并保留原始异常")
  void shouldDeactivateAndRethrow_whenRuntimeInitializationFailsAfterRegister() throws Exception {
    BatchPlatformClient client = BatchPlatformClient.builder(cfg())
        .register(new IdempotentHandlerWithoutStore())
        .build();
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.register(any())).thenReturn(null);
    inject(client, "httpClient", http);

    assertThatThrownBy(client::start)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no SdkIdempotencyStore");

    verify(http).deactivate(anyString(), any(), any(Duration.class));
    verify(http).evictIdleConnections();
    assertThat(field(client, "started")).isEqualTo(false);
    assertThat(field(client, "dispatcher")).isNull();
    assertThat(client.isHealthy()).isFalse();
  }

  @Test
  @DisplayName("注册成功后启动全部运行组件,停止时按生命周期回收")
  void shouldStartAndStopAllRuntimeComponents_whenRegistrationSucceeds() throws Exception {
    BatchPlatformClient client =
        BatchPlatformClient.builder(cfg()).register(stub("type-a")).build();
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.register(any())).thenReturn(null);
    inject(client, "httpClient", http);

    try (MockedConstruction<TaskDispatcher> dispatchers = mockConstruction(TaskDispatcher.class);
        MockedConstruction<KafkaTaskConsumer> consumers =
            mockConstruction(KafkaTaskConsumer.class);
        MockedConstruction<HeartbeatScheduler> heartbeats =
            mockConstruction(HeartbeatScheduler.class);
        MockedConstruction<LeaseRenewalScheduler> renewals =
            mockConstruction(LeaseRenewalScheduler.class)) {
      client.start();

      assertThat(field(client, "started")).isEqualTo(true);
      assertThat(dispatchers.constructed()).hasSize(1);
      assertThat(consumers.constructed()).hasSize(1);
      assertThat(heartbeats.constructed()).hasSize(1);
      assertThat(renewals.constructed()).hasSize(1);
      verify(heartbeats.constructed().getFirst()).start();
      verify(renewals.constructed().getFirst()).start();

      client.stop(Duration.ofSeconds(1));

      assertThat(field(client, "started")).isEqualTo(false);
      verify(http).deactivate(anyString(), any(), any(Duration.class));
      verify(http).evictIdleConnections();
    }
  }

  @Test
  @DisplayName("已 start 后再 start → 抛 IllegalStateException")
  void shouldThrowIllegalState_whenStartedTwice() throws Exception {
    // 准备:用反射把 started 置 true 模拟已启动,避免真拉 Kafka
    BatchPlatformClient client =
        BatchPlatformClient.builder(cfg()).register(stub("type-a")).build();
    inject(client, "started", true);

    // 执行并断言
    assertThatThrownBy(client::start)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already started");
  }

  @Test
  @DisplayName("仅在显式开启时能力标签才追加 dry-run 安全标记")
  void shouldAddDryRunTag_whenExplicitlyOptedIn() {
    BatchPlatformClient defaultClient =
        BatchPlatformClient.builder(cfg()).register(stub("type-a")).build();
    BatchPlatformClient safeClient = BatchPlatformClient.builder(
            cfg().toBuilder().dryRunSafe(true).build())
        .register(stub("type-a"))
        .build();

    assertThat(defaultClient.capabilityTags()).containsExactly("type-a");
    assertThat(safeClient.capabilityTags())
        .containsExactly("type-a", SdkWorkerCapabilities.DRY_RUN_SAFE);
  }
}
