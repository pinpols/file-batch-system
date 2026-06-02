package com.example.batch.ext.sample.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.sdk.testkit.FakeBatchPlatform;
import com.example.batch.sdk.testkit.RecordedReport;
import com.example.batch.sdk.testkit.TaskDispatchMessageBuilder;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * ADR-035 自托管 worker(Spring Boot 接入)端到端集成测。
 *
 * <p>跑通的链路:starter 自动从 {@code batch.worker-sdk.*} 绑定 config → 容器收集 {@code EchoHandler} /
 * {@code SleepHandler} bean 自动注册 → {@code SmartLifecycle} 启动 client → {@link FakeBatchPlatform} 经
 * 内嵌 Kafka 派单 → handler 执行 → report 回到 HTTP stub。
 *
 * <p>本 IT 不连外部服务,跑完 <10s。
 */
@SpringBootTest(
    classes = SampleSpringWorkerApplication.class,
    properties = {
      "batch.worker-sdk.enabled=true",
      "batch.worker-sdk.tenant-id=tenant-a",
      "batch.worker-sdk.worker-code=spring-worker-1",
      "batch.worker-sdk.kafka-group-id=spring-worker-1",
      "batch.worker-sdk.heartbeat-interval=2s",
      "batch.worker-sdk.lease-renew-interval=2s",
      "batch.worker-sdk.kafka-poll-interval=100ms",
      "batch.worker-sdk.max-concurrent-tasks=2"
    })
class SampleSpringWorkerIT {

  // 静态生命周期:@DynamicPropertySource 在 Spring 容器启动前求值,需要这时 broker / stub 已就绪。
  private static final FakeBatchPlatform PLATFORM = FakeBatchPlatform.start();

  @AfterAll
  static void closePlatform() {
    PLATFORM.close();
  }

  @DynamicPropertySource
  static void wirePlatform(DynamicPropertyRegistry registry) {
    registry.add("batch.worker-sdk.base-url", PLATFORM::baseUrl);
    registry.add("batch.worker-sdk.kafka-bootstrap", PLATFORM::kafkaBootstrap);
    registry.add(
        "batch.worker-sdk.kafka-topic-pattern", () -> FakeBatchPlatform.DISPATCH_TOPIC_PATTERN);
  }

  @Test
  @DisplayName("starter 启动期上报 register,handler bean 自动登记")
  void registersHandlers_onStartup() {
    // 容器启动已经走过 register;starter 不依赖派单消息就会上报一次
    List<Map<String, Object>> registrations = PLATFORM.registrations();
    assertThat(registrations).as("starter 应触发至少一次 register").isNotEmpty();
  }

  @Test
  @DisplayName("@Component EchoHandler 被自动注册并能消费派单")
  void echoHandler_endToEnd() {
    PLATFORM.dispatch(
        TaskDispatchMessageBuilder.dispatch("echo")
            .tenantId("tenant-a")
            .taskId(401L)
            .param("x", "y")
            .build());

    RecordedReport report = PLATFORM.awaitReport(401L, Duration.ofSeconds(10));
    assertThat(report.success()).isTrue();
    assertThat(report.message()).isEqualTo("echoed");
    assertThat(report.outputs()).containsEntry("x", "y");
    assertThat(PLATFORM.claims()).contains(401L);
  }

  @Test
  @DisplayName("@Component SleepHandler 同样自动注册并跑完")
  void sleepHandler_endToEnd() {
    PLATFORM.dispatch(
        TaskDispatchMessageBuilder.dispatch("sleep")
            .tenantId("tenant-a")
            .taskId(402L)
            .param("millis", 50)
            .build());

    RecordedReport report = PLATFORM.awaitReport(402L, Duration.ofSeconds(10));
    assertThat(report.success()).isTrue();
    assertThat(report.message()).isEqualTo("slept 50ms");
    assertThat(report.outputs()).containsEntry("millis", 50);
  }

  @Test
  @DisplayName("未注册的 taskType 不会让平台收到 success report")
  void unknownTaskType_noSuccessReport() {
    long taskId = 403L;
    PLATFORM.dispatch(
        TaskDispatchMessageBuilder.dispatch("not_registered_type")
            .tenantId("tenant-a")
            .taskId(taskId)
            .build());

    // SDK 对未匹配的 taskType 不会执行(也不会上报 success);给 2s 缓冲后断言没有 success report
    try {
      Thread.sleep(2000);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
    RecordedReport report = PLATFORM.reports().get(taskId);
    if (report != null) {
      assertThat(report.success())
          .as("未注册的 taskType 不应成功")
          .isFalse();
    }
  }
}
