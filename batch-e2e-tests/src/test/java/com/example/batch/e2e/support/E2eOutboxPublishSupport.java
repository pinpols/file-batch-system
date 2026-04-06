package com.example.batch.e2e.support;

import com.example.batch.common.kafka.BatchTopics;
import com.example.batch.common.kafka.TaskDispatchMessage;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.orchestrator.application.engine.OutboxPublisher;
import com.example.batch.orchestrator.config.BatchMqTopicsProperties;
import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.domain.query.OutboxEventQuery;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publishes pending outbox rows to Kafka (integration tests disable the forwarder scheduler).
 */
@Component
public class E2eOutboxPublishSupport {

    private final OutboxPublisher outboxPublisher;
    private final OutboxEventMapper outboxEventMapper;
    private final BatchMqTopicsProperties batchMqTopicsProperties;

    private final String kafkaBootstrapServers;

    public E2eOutboxPublishSupport(OutboxPublisher outboxPublisher,
                                   OutboxEventMapper outboxEventMapper,
                                   BatchMqTopicsProperties batchMqTopicsProperties,
                                   @Value("${spring.kafka.bootstrap-servers}")
                                   String kafkaBootstrapServers) {
        this.outboxPublisher = outboxPublisher;
        this.outboxEventMapper = outboxEventMapper;
        this.batchMqTopicsProperties = batchMqTopicsProperties;
        this.kafkaBootstrapServers = kafkaBootstrapServers;
    }

    public void publishAllPending(String tenantId) {
        List<OutboxEventEntity> pending = outboxEventMapper.selectPending(
                new OutboxEventQuery(tenantId, null, null, null,
                        OutboxPublishStatus.NEW.code(), OutboxPublishStatus.FAILED.code(),
                        500, 1, 0));
        ensureTopicsExist(pending);
        for (OutboxEventEntity event : pending) {
            outboxPublisher.publish(event);
        }
    }

    private void ensureTopicsExist(List<OutboxEventEntity> pendingEvents) {
        Set<String> topics = pendingEvents.stream()
                .map(this::resolveTargetTopic)
                .filter(topic -> topic != null && !topic.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (topics.isEmpty()) {
            return;
        }
        Map<String, Object> config = Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
        try (AdminClient adminClient = AdminClient.create(config)) {
            List<NewTopic> newTopics = topics.stream()
                    .map(topic -> new NewTopic(topic, 1, (short) 1))
                    .toList();
            adminClient.createTopics(newTopics).all().get();
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof TopicExistsException) {
                return;
            }
            throw new IllegalStateException("Failed to create e2e Kafka topics: " + topics, ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to create e2e Kafka topics: " + topics, ex);
        }
    }

    private String resolveTargetTopic(OutboxEventEntity event) {
        String baseTopic = batchMqTopicsProperties.resolveDispatchTopic(event.getEventType());
        if (baseTopic == null || baseTopic.isBlank()) {
            return null;
        }
        TaskDispatchMessage message = JsonUtils.fromJson(event.getPayloadJson(), TaskDispatchMessage.class);
        if (message == null || message.selectedWorkerId() == null || message.selectedWorkerId().isBlank()) {
            return baseTopic;
        }
        return BatchTopics.directDispatchTopic(baseTopic, message.selectedWorkerId());
    }
}
