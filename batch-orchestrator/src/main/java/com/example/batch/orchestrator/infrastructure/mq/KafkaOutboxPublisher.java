package com.example.batch.orchestrator.infrastructure.mq;

import com.example.batch.common.kafka.BatchEventMessage;
import com.example.batch.common.kafka.BatchMessageType;
import com.example.batch.common.kafka.BatchTopics;
import com.example.batch.common.kafka.TaskDispatchMessage;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.application.engine.OutboxPublisher;
import com.example.batch.orchestrator.config.BatchMqTopicsProperties;
import com.example.batch.orchestrator.config.OutboxProperties;
import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KafkaOutboxPublisher implements OutboxPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final BatchMqTopicsProperties batchMqTopicsProperties;
    private final OutboxProperties outboxProperties;

    @Override
    public boolean publish(OutboxEventEntity event) {
        String topic = batchMqTopicsProperties.resolveDispatchTopic(event.getEventType());
        if (topic != null) {
            TaskDispatchMessage dispatchMessage = JsonUtils.fromJson(event.getPayloadJson(), TaskDispatchMessage.class);
            String targetTopic = dispatchMessage != null && dispatchMessage.selectedWorkerId() != null
                    ? BatchTopics.directDispatchTopic(topic, dispatchMessage.selectedWorkerId())
                    : topic;
            kafkaTemplate.send(targetTopic, event.getEventKey(), event.getPayloadJson());
            return true;
        }

        String fallbackTopic = outboxProperties.getDefaultTopic();
        BatchEventMessage message = new BatchEventMessage(
                "v1",
                BatchMessageType.OUTBOX_EVENT,
                event.getTenantId(),
                null,
                null,
                null,
                null,
                null,
                event.getTraceId(),
                event.getEventKey(),
                event.getAggregateType(),
                outboxProperties.getProducerName(),
                event.getEventType(),
                fallbackTopic,
                event.getEventKey(),
                event.getCreatedAt(),
                Map.of("payload", JsonUtils.fromJson(event.getPayloadJson(), Object.class)),
                Map.of("aggregateId", event.getAggregateId())
        );
        kafkaTemplate.send(fallbackTopic, event.getEventKey(), JsonUtils.toJson(message));
        return true;
    }
}
