package io.github.pinpols.batch.worker.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

@DisplayName("Worker 启动期配置绑定与参数校验")
class WorkerPropertiesBindingTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
      .withUserConfiguration(BindingConfiguration.class)
      .withPropertyValues("spring.kafka.bootstrap-servers=localhost:9092");

  @Test
  @DisplayName("缺省配置保留 Kafka 拉取与监听参数及 Worker 并发和续租参数")
  void shouldBindDefaults() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      WorkerKafkaProperties kafka = context.getBean(WorkerKafkaProperties.class);
      assertThat(kafka.getBootstrapServers()).isEqualTo("localhost:9092");
      assertThat(kafka.getConsumer().getAutoOffsetReset()).isEqualTo("latest");
      assertThat(kafka.getConsumer().getMaxPollRecords()).isEqualTo(20);
      assertThat(kafka.getConsumer().getFetchMinSize()).isEqualTo(1024);
      assertThat(kafka.getConsumer().getFetchMaxWait()).isEqualTo(500);
      assertThat(kafka.getConsumer().getMaxPollIntervalMs()).isEqualTo(600_000);
      assertThat(kafka.getConsumer().getMetadataMaxAgeMs()).isEqualTo(30_000);
      assertThat(kafka.getListener().getConcurrency()).isEqualTo(1);
      assertThat(context.getBean(WorkerConcurrencyProperties.class).getMaxConcurrentTasks())
          .isPositive();
      WorkerLeaseProperties lease = context.getBean(WorkerLeaseProperties.class);
      assertThat(lease.getConsecutiveFailureAlertThreshold()).isEqualTo(3);
      assertThat(lease.getCircuitHalfOpenTickInterval()).isEqualTo(5);
      assertThat(lease.getRenewBatchMaxItems()).isEqualTo(256);
    });
  }

  @Test
  @DisplayName("现有配置键完整绑定, Kafka 允许零字节和零等待配置")
  void shouldBindExistingKeysAndZeroValues() {
    runner
        .withPropertyValues(
            "spring.kafka.consumer.auto-offset-reset=earliest",
            "spring.kafka.consumer.max-poll-records=4",
            "spring.kafka.consumer.fetch-min-size=0",
            "spring.kafka.consumer.fetch-max-wait=0",
            "spring.kafka.consumer.max-poll-interval-ms=1200000",
            "spring.kafka.consumer.metadata-max-age-ms=0",
            "spring.kafka.listener.concurrency=2",
            "batch.worker.max-concurrent-tasks=8",
            "batch.worker.lease.consecutive-failure-alert-threshold=4",
            "batch.worker.lease.circuit-half-open-tick-interval=6",
            "batch.worker.lease.renew-batch-max-items=128")
        .run(context -> {
          assertThat(context).hasNotFailed();
          WorkerKafkaProperties kafka = context.getBean(WorkerKafkaProperties.class);
          assertThat(kafka.getConsumer().getAutoOffsetReset()).isEqualTo("earliest");
          assertThat(kafka.getConsumer().getMaxPollRecords()).isEqualTo(4);
          assertThat(kafka.getConsumer().getFetchMinSize()).isZero();
          assertThat(kafka.getConsumer().getFetchMaxWait()).isZero();
          assertThat(kafka.getConsumer().getMaxPollIntervalMs()).isEqualTo(1_200_000);
          assertThat(kafka.getConsumer().getMetadataMaxAgeMs()).isZero();
          assertThat(kafka.getListener().getConcurrency()).isEqualTo(2);
          assertThat(context.getBean(WorkerConcurrencyProperties.class).getMaxConcurrentTasks())
              .isEqualTo(8);
          WorkerLeaseProperties lease = context.getBean(WorkerLeaseProperties.class);
          assertThat(lease.getConsecutiveFailureAlertThreshold()).isEqualTo(4);
          assertThat(lease.getCircuitHalfOpenTickInterval()).isEqualTo(6);
          assertThat(lease.getRenewBatchMaxItems()).isEqualTo(128);
        });
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "spring.kafka.bootstrap-servers=",
        "spring.kafka.consumer.auto-offset-reset=",
        "spring.kafka.consumer.max-poll-records=0",
        "spring.kafka.consumer.fetch-min-size=-1",
        "spring.kafka.consumer.fetch-max-wait=-1",
        "spring.kafka.consumer.max-poll-interval-ms=0",
        "spring.kafka.consumer.metadata-max-age-ms=-1",
        "spring.kafka.listener.concurrency=0",
        "batch.worker.max-concurrent-tasks=0",
        "batch.worker.lease.consecutive-failure-alert-threshold=0",
        "batch.worker.lease.circuit-half-open-tick-interval=0",
        "batch.worker.lease.renew-batch-max-items=0"
      })
  @DisplayName("不可执行的参数在绑定期拒绝, 不等到监听或续租启动")
  void shouldRejectInvalidValues(String property) {
    runner.withPropertyValues(property).run(context -> {
      assertThat(context).hasFailed();
      assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
    });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({
    WorkerKafkaProperties.class,
    WorkerConcurrencyProperties.class,
    WorkerLeaseProperties.class
  })
  static class BindingConfiguration {}
}
