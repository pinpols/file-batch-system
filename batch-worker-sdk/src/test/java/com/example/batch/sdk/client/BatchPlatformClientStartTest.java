package com.example.batch.sdk.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.sdk.internal.PlatformHttpClient;
import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskResult;
import java.io.IOException;
import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    // arrange
    BatchPlatformClient client =
        BatchPlatformClient.builder(cfg()).register(stub("type-a")).build();
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.register(any())).thenThrow(new IOException("orchestrator unreachable"));
    inject(client, "httpClient", http);

    // act + assert:抛 RuntimeException(register 失败让进程非 0 退出,K8s 重启)
    assertThatThrownBy(client::start)
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("worker register failed")
        .hasCauseInstanceOf(IOException.class);

    // assert:无任何后台线程被装配,状态反映未就绪
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
  }

  @Test
  @DisplayName("已 start 后再 start → 抛 IllegalStateException")
  void shouldThrowIllegalState_whenStartedTwice() throws Exception {
    // arrange:用反射把 started 置 true 模拟已启动,避免真拉 Kafka
    BatchPlatformClient client =
        BatchPlatformClient.builder(cfg()).register(stub("type-a")).build();
    inject(client, "started", true);

    // act + assert
    assertThatThrownBy(client::start)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already started");
  }
}
