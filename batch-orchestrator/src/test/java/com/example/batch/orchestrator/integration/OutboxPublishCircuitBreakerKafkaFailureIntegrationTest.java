package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.application.engine.DefaultScheduleForwarder;
import com.example.batch.orchestrator.application.engine.ScheduleForwarderResult;
import com.example.batch.orchestrator.application.plan.SchedulePlan;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.infrastructure.mq.OutboxPublishCircuitBreaker;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import com.example.batch.testing.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "batch.outbox.circuit-breaker-enabled=true",
      "batch.outbox.circuit-breaker-failure-threshold-consecutive-polls=2",
      "batch.outbox.circuit-breaker-cooldown-millis=60000",
      "batch.outbox.retry-delay-seconds=0",
      "batch.outbox.retry-jitter-ratio=0",
      "batch.outbox.max-retry-attempts=10",
      "batch.outbox.batch-size=10",
    })
@Import(
    OutboxPublishCircuitBreakerKafkaFailureIntegrationTest.FastFailKafkaProducerConfiguration.class)
// 该 IT 通过 stop/start Kafka 容器注入故障，重启后端口变化，Spring 缓存的 KafkaTemplate 仍指向旧端口；
// 标 DirtiesContext 避免污染后续 IT（如 OutboxPublishIntegrationTest）。
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxPublishCircuitBreakerKafkaFailureIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "it-cb";
  private static final String FIELD_FAILED_POLLS = "failedPolls";
  private static final String FIELD_OPEN_UNTIL_MS = "openUntilMs";

  @Autowired private DefaultScheduleForwarder scheduleForwarder;
  @Autowired private OutboxPublishCircuitBreaker circuitBreaker;
  @Autowired private OutboxEventMapper outboxEventMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void kafkaBrokerFailureOpensOutboxCircuitBreakerAfterThreshold() {
    assertThat(circuitBreaker.allowNow()).isTrue();

    stopKafkaForFaultInjection();
    try {
      OutboxEventEntity first = seedOutboxEvent("cb-fail-1-" + System.nanoTime());
      ScheduleForwarderResult firstResult = scheduleForwarder.advance(plan());
      circuitBreaker.onAdvanceResult(firstResult.totalFailures());
      assertThat(firstResult.totalFailures()).isGreaterThanOrEqualTo(1);
      assertStatus(first.getId(), OutboxPublishStatus.FAILED.code());
      assertThat(circuitBreaker.allowNow()).isTrue();

      OutboxEventEntity second = seedOutboxEvent("cb-fail-2-" + System.nanoTime());
      ScheduleForwarderResult secondResult = scheduleForwarder.advance(plan());
      circuitBreaker.onAdvanceResult(secondResult.totalFailures());
      assertThat(secondResult.totalFailures()).isGreaterThanOrEqualTo(1);
      assertStatus(second.getId(), OutboxPublishStatus.FAILED.code());

      assertThat(circuitBreaker.allowNow()).isFalse();
    } finally {
      startKafkaAfterFaultInjection();
      circuitBreaker.onAdvanceResult(0);
    }
  }

  private OutboxEventEntity seedOutboxEvent(String key) {
    OutboxEventEntity event = new OutboxEventEntity();
    event.setTenantId(TENANT);
    event.setAggregateType("CIRCUIT_BREAKER_IT");
    event.setAggregateId(System.nanoTime());
    event.setEventType("CIRCUIT_BREAKER_IT_EVENT");
    event.setEventKey(key);
    event.setPayloadJson("{\"kind\":\"circuit-breaker-it\"}");
    event.setPublishStatus(OutboxPublishStatus.NEW.code());
    event.setPublishAttempt(0);
    event.setPriority(10);
    event.setNextPublishAt(BatchDateTimeSupport.utcNow());
    event.setTraceId("trace-" + key);
    outboxEventMapper.insert(event);
    return event;
  }

  private static SchedulePlan plan() {
    SchedulePlan plan = new SchedulePlan();
    plan.setTenantId(TENANT);
    plan.setShardTotal(1);
    plan.setShardIndex(0);
    return plan;
  }

  private void assertStatus(Long eventId, String expected) {
    String status =
        jdbcTemplate.queryForObject(
            "select publish_status from batch.outbox_event where id = ?", String.class, eventId);
    assertThat(status).isEqualTo(expected);
  }

  @TestConfiguration
  static class FastFailKafkaProducerConfiguration {

    @Bean
    @Primary
    OutboxPublishCircuitBreaker testCircuitBreaker(
        BatchOrchestratorGovernanceProperties governance) {
      return new OutboxPublishCircuitBreaker(governance, new InMemoryCircuitRedisSupport());
    }

    @Bean
    @Primary
    ProducerFactory<String, String> fastFailProducerFactory() {
      Map<String, Object> properties = new HashMap<>();
      properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers());
      properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
      properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
      properties.put(ProducerConfig.ACKS_CONFIG, "all");
      properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
      properties.put(ProducerConfig.RETRIES_CONFIG, 0);
      properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000);
      properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000);
      properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3000);
      properties.put(ProducerConfig.LINGER_MS_CONFIG, 0);
      return new DefaultKafkaProducerFactory<>(properties);
    }

    @Bean
    @Primary
    KafkaTemplate<String, String> fastFailKafkaTemplate(
        ProducerFactory<String, String> producerFactory, ObservationRegistry observationRegistry) {
      KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory);
      template.setObservationEnabled(true);
      template.setObservationRegistry(observationRegistry);
      return template;
    }
  }

  private static final class InMemoryCircuitRedisSupport extends OrchestratorRedisSupport {

    private final ConcurrentMap<String, Long> values = new ConcurrentHashMap<>();

    private InMemoryCircuitRedisSupport() {
      super(new StringRedisTemplate(), new ObjectMapper());
    }

    @Override
    public Long evalLong(String script, String key, String... args) {
      if (args.length == 1 && FIELD_OPEN_UNTIL_MS.equals(args[0])) {
        return values.getOrDefault(FIELD_OPEN_UNTIL_MS, 0L);
      }
      if (args.length == 7
          && FIELD_FAILED_POLLS.equals(args[0])
          && FIELD_OPEN_UNTIL_MS.equals(args[1])) {
        long failed = Long.parseLong(args[2]);
        long threshold = Long.parseLong(args[3]);
        long cooldownMillis = Long.parseLong(args[4]);
        long now = Long.parseLong(args[5]);
        long openUntil = values.getOrDefault(FIELD_OPEN_UNTIL_MS, 0L);
        if (failed > 0) {
          long failedPolls = values.getOrDefault(FIELD_FAILED_POLLS, 0L) + 1;
          if (failedPolls >= threshold) {
            openUntil = now + cooldownMillis;
            failedPolls = 0L;
          }
          values.put(FIELD_FAILED_POLLS, failedPolls);
          values.put(FIELD_OPEN_UNTIL_MS, openUntil);
          return openUntil;
        }
        values.put(FIELD_FAILED_POLLS, 0L);
        values.put(FIELD_OPEN_UNTIL_MS, 0L);
        return 0L;
      }
      throw new IllegalArgumentException("Unexpected circuit breaker script invocation");
    }
  }
}
