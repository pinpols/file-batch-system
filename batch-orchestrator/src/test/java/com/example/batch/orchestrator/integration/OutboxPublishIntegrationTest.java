package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.kafka.BatchTopics;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.application.engine.OutboxPublisher;
import com.example.batch.orchestrator.domain.entity.EventDeliveryLogEntity;
import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.domain.query.EventDeliveryLogQuery;
import com.example.batch.orchestrator.mapper.EventDeliveryLogMapper;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration test: OutboxEventEntity → KafkaOutboxPublisher → Kafka topic + EventDeliveryLog.
 *
 * <p>Schedules are disabled in application-test.yml (poll-interval-millis: 600000) so this test
 * drives publishing manually via {@link OutboxPublisher}.
 */
@SpringBootTest(
        classes = BatchOrchestratorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutboxPublishIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private OutboxEventMapper outboxEventMapper;

    @Autowired
    private EventDeliveryLogMapper eventDeliveryLogMapper;

    @Test
    void shouldPublishFallbackEventToDefaultTopicAndPersistDeliveryLog() {
        OutboxEventEntity event = pendingEvent("CUSTOM_EVENT_TYPE", "AGG_TYPE", "key-fallback-001");
        outboxEventMapper.insert(event);

        boolean published = outboxPublisher.publish(event);

        assertThat(published).isTrue();

        List<EventDeliveryLogEntity> logs = eventDeliveryLogMapper.selectByQuery(
                new EventDeliveryLogQuery("t1", OutboxPublishStatus.PUBLISHED.code(), "CUSTOM_EVENT_TYPE", "key-fallback-001"));
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getTargetTopic()).isEqualTo(BatchTopics.OUTBOX_EVENT);

        try (KafkaConsumer<String, String> consumer = buildConsumer("fallback-test-" + System.currentTimeMillis())) {
            consumer.subscribe(List.of(BatchTopics.OUTBOX_EVENT));
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
            assertThat(records.count()).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void shouldPublishImportDispatchEventToImportTopicAndPersistDeliveryLog() {
        OutboxEventEntity event = pendingEvent("IMPORT", "JOB_PARTITION", "key-import-001");
        outboxEventMapper.insert(event);

        boolean published = outboxPublisher.publish(event);

        assertThat(published).isTrue();

        List<EventDeliveryLogEntity> logs = eventDeliveryLogMapper.selectByQuery(
                new EventDeliveryLogQuery("t1", OutboxPublishStatus.PUBLISHED.code(), "IMPORT", "key-import-001"));
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getTargetTopic()).isEqualTo(BatchTopics.TASK_DISPATCH_IMPORT);

        try (KafkaConsumer<String, String> consumer = buildConsumer("import-test-" + System.currentTimeMillis())) {
            consumer.subscribe(List.of(BatchTopics.TASK_DISPATCH_IMPORT));
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
            assertThat(records.count()).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void shouldPublishExportDispatchEventToExportTopicAndPersistDeliveryLog() {
        OutboxEventEntity event = pendingEvent("EXPORT", "JOB_PARTITION", "key-export-001");
        outboxEventMapper.insert(event);

        boolean published = outboxPublisher.publish(event);

        assertThat(published).isTrue();

        List<EventDeliveryLogEntity> logs = eventDeliveryLogMapper.selectByQuery(
                new EventDeliveryLogQuery("t1", OutboxPublishStatus.PUBLISHED.code(), "EXPORT", "key-export-001"));
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getTargetTopic()).isEqualTo(BatchTopics.TASK_DISPATCH_EXPORT);
    }

    // --- helpers ---

    private static OutboxEventEntity pendingEvent(String eventType, String aggregateType, String eventKey) {
        OutboxEventEntity e = new OutboxEventEntity();
        e.setTenantId("t1");
        e.setAggregateType(aggregateType);
        e.setAggregateId(1L);
        e.setEventType(eventType);
        e.setEventKey(eventKey);
        e.setPayloadJson("{\"test\":true}");
        e.setPublishStatus(OutboxPublishStatus.NEW.code());
        e.setPublishAttempt(0);
        e.setNextPublishAt(Instant.now());
        e.setTraceId("trace-it-test");
        return e;
    }

    private static KafkaConsumer<String, String> buildConsumer(String groupId) {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, groupId,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
        ));
    }
}
