package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.LaunchEnvelope;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.kafka.BatchKafkaTopicNamespace;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.task.LaunchApplicationService;
import io.github.pinpols.batch.orchestrator.application.trigger.TriggerLaunchRecoveryService;
import io.github.pinpols.batch.orchestrator.config.OrchestratorKafkaConsumerConfiguration;
import io.github.pinpols.batch.orchestrator.config.TriggerConsumerProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.infrastructure.trigger.TriggerLaunchConsumer;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
// park 之后后台恢复扫描会用真实 LaunchApplicationService 重放锚点；本用例用不存在的 job 模拟“稍后恢复”，
// 若 30s 轮询恰好命中会把锚点写成 REJECTED。测试内拉长轮询间隔，保证 new 锚点断言的是 PENDING 而非扫描结果。
@TestPropertySource(properties = "batch.trigger.launch-recovery.poll-interval-millis=3600000")
@DisplayName("启动恢复锚点与真实 Kafka offset")
class TriggerRecoveryKafkaOffsetIntegrationTest extends AbstractIntegrationTest {
  private final TriggerLaunchRecoveryService service;

  private final ApplicationContext context;

  private final JdbcTemplate jdbc;

  @Autowired
  TriggerRecoveryKafkaOffsetIntegrationTest(
      TriggerLaunchRecoveryService service, ApplicationContext context, JdbcTemplate jdbc) {
    this.service = service;
    this.context = context;
    this.jdbc = jdbc;
  }

  @Test
  @DisplayName("锚点登记成功可提交，冲突载荷不得提交或覆盖")
  void shouldConfirmOffset_whenDurableAnchorMatches() throws Exception {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String tenant = "kafka-anchor-" + suffix;
    String original = envelope(tenant, "existing", "job-a");
    assertThat(service.park(original, "trace")).isTrue();
    String sql =
        "update batch.trigger_launch_recovery set recovery_status='DONE' where tenant_id=? and request_id=?";
    jdbc.update(sql, tenant, "existing");
    String conflict = envelope(tenant, "existing", "job-b");
    exercise(suffix + "-conflict", conflict, false);
    String readSql =
        "select recovery_status from batch.trigger_launch_recovery where tenant_id=? and request_id=?";
    assertThat(jdbc.queryForObject(readSql, String.class, tenant, "existing")).isEqualTo("DONE");

    String fresh = envelope(tenant, "new", "job-c");
    exercise(suffix + "-fresh", fresh, true);
    assertThat(jdbc.queryForObject(readSql, String.class, tenant, "new")).isEqualTo("PENDING");
  }

  private void exercise(String suffix, String payload, boolean shouldCommit) throws Exception {
    String topic = "anchor-" + suffix;
    String group = "anchor-group-" + suffix;
    Map<String, Object> broker = Map.of("bootstrap.servers", KAFKA.getBootstrapServers());
    try (AdminClient admin = AdminClient.create(broker)) {
      admin
          .createTopics(List.of(new NewTopic(topic, 1, (short) 1)))
          .all()
          .get(20, TimeUnit.SECONDS);
      TriggerConsumerProperties props = new TriggerConsumerProperties();
      props.setGroupId(group);
      props.getErrorHandler().setRetryBackoffMs(200);
      props.getErrorHandler().setRetryAttempts(1);
      SimpleMeterRegistry meters = new SimpleMeterRegistry();
      var configuration = new OrchestratorKafkaConsumerConfiguration(
          props, meters, context.getBeanProvider(TriggerLaunchRecoveryService.class));
      Method factory = OrchestratorKafkaConsumerConfiguration.class.getDeclaredMethod(
          "triggerLaunchErrorHandler");
      factory.setAccessible(true);
      DefaultErrorHandler handler = (DefaultErrorHandler) factory.invoke(configuration);
      ContainerProperties containerProperties = new ContainerProperties(topic);
      containerProperties.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
      containerProperties.setPollTimeout(200);
      KafkaMessageListenerContainer<String, String> container = new KafkaMessageListenerContainer<>(
          configuration.triggerLaunchConsumerFactory(KAFKA.getBootstrapServers()),
          containerProperties);
      container.setCommonErrorHandler(handler);
      AtomicInteger deliveries = new AtomicInteger();
      LaunchApplicationService launchService = mock(LaunchApplicationService.class);
      when(launchService.launchFromTrustedQueue(any())).thenAnswer(invocation -> {
        deliveries.incrementAndGet();
        throw new IllegalStateException("injected transient launch failure");
      });
      TriggerLaunchConsumer consumer = new TriggerLaunchConsumer(
          launchService,
          context.getBean(OrchestratorGracefulShutdown.class),
          meters,
          props,
          context.getBean(BatchKafkaTopicNamespace.class));
      container.setupMessageListener(
          (AcknowledgingMessageListener<String, String>) consumer::consume);
      container.start();
      try {
        ContainerTestUtils.waitForAssignment(container, 1);
        Map<String, Object> producerConfig = Map.of(
            "bootstrap.servers",
            KAFKA.getBootstrapServers(),
            "key.serializer",
            "org.apache.kafka.common.serialization.StringSerializer",
            "value.serializer",
            "org.apache.kafka.common.serialization.StringSerializer");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerConfig)) {
          producer.send(new ProducerRecord<>(topic, "key", payload)).get(20, TimeUnit.SECONDS);
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        OffsetAndMetadata committed = null;
        while (System.nanoTime() < deadline) {
          var exhausted = meters
              .find("batch.trigger.launch.failed.total")
              .tag("reason", "retries_exhausted")
              .counter();
          committed = admin
              .listConsumerGroupOffsets(group)
              .partitionsToOffsetAndMetadata()
              .get(5, TimeUnit.SECONDS)
              .get(new TopicPartition(topic, 0));
          if (shouldCommit && committed != null && committed.offset() == 1) break;
          if (!shouldCommit && exhausted != null && exhausted.count() >= 2 && deliveries.get() >= 2)
            break;
          Thread.sleep(100);
        }
        if (shouldCommit) {
          assertThat(committed).isNotNull();
          assertThat(committed.offset()).isEqualTo(1);
        } else {
          assertThat(deliveries.get()).isGreaterThanOrEqualTo(2);
          assertThat(committed == null || committed.offset() == 0).isTrue();
        }
      } finally {
        container.stop();
      }
    }
  }

  private static String envelope(String tenant, String requestId, String job) {
    LaunchRequest request = new LaunchRequest(
        tenant, job, LocalDate.of(2026, 10, 30), TriggerType.MANUAL, requestId, "trace", Map.of());
    return JsonUtils.toJson(LaunchEnvelope.of(
        request, tenant + ":" + requestId, Instant.parse("2026-10-30T00:00:00Z")));
  }
}
