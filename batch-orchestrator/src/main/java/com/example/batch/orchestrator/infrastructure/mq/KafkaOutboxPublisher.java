package com.example.batch.orchestrator.infrastructure.mq;

import com.example.batch.common.kafka.BatchEventMessage;
import com.example.batch.common.kafka.BatchMessageType;
import com.example.batch.common.kafka.BatchTopics;
import com.example.batch.common.kafka.TaskDispatchMessage;
import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.application.engine.OutboxPublisher;
import com.example.batch.orchestrator.config.BatchMqTopicsProperties;
import com.example.batch.orchestrator.config.OutboxProperties;
import com.example.batch.orchestrator.domain.entity.EventDeliveryLogEntity;
import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.mapper.EventDeliveryLogMapper;
import java.util.Map;
import java.util.LinkedHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KafkaOutboxPublisher implements OutboxPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final BatchMqTopicsProperties batchMqTopicsProperties;
    private final OutboxProperties outboxProperties;
    private final EventDeliveryLogMapper eventDeliveryLogMapper;

    @Override
    public boolean publish(OutboxEventEntity event) {
        String topic = batchMqTopicsProperties.resolveDispatchTopic(event.getEventType());
        if (topic != null) {
            TaskDispatchMessage dispatchMessage = JsonUtils.fromJson(event.getPayloadJson(), TaskDispatchMessage.class);
            String targetTopic = dispatchMessage != null && dispatchMessage.selectedWorkerId() != null
                    ? BatchTopics.directDispatchTopic(topic, dispatchMessage.selectedWorkerId())
                    : topic;
            try {
                kafkaTemplate.send(targetTopic, event.getEventKey(), event.getPayloadJson()).toCompletableFuture().join();
                recordDelivery(event, targetTopic, dispatchMessage == null ? null : dispatchMessage.selectedWorkerId(),
                        OutboxPublishStatus.PUBLISHED.code(), null);
                return true;
            } catch (RuntimeException exception) {
                recordDelivery(event, targetTopic, dispatchMessage == null ? null : dispatchMessage.selectedWorkerId(),
                        OutboxPublishStatus.FAILED.code(), exception.getMessage());
                return false;
            }
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
        try {
            kafkaTemplate.send(fallbackTopic, event.getEventKey(), JsonUtils.toJson(message)).toCompletableFuture().join();
            recordDelivery(event, fallbackTopic, null, OutboxPublishStatus.PUBLISHED.code(), null);
            return true;
        } catch (RuntimeException exception) {
            recordDelivery(event, fallbackTopic, null, OutboxPublishStatus.FAILED.code(), exception.getMessage());
            return false;
        }
    }

    private void recordDelivery(OutboxEventEntity event,
                                String targetTopic,
                                String targetWorkerId,
                                String deliveryStatus,
                                String errorMessage) {
        EventDeliveryLogEntity log = new EventDeliveryLogEntity();
        log.setTenantId(event.getTenantId());
        log.setOutboxEventId(event.getId());
        log.setEventType(event.getEventType());
        log.setEventKey(event.getEventKey());
        log.setTargetTopic(targetTopic);
        log.setTargetWorkerId(targetWorkerId);
        log.setDeliveryStatus(deliveryStatus);
        log.setDeliveryAttempt(event.getPublishAttempt() == null ? 1 : event.getPublishAttempt() + 1);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("aggregateType", event.getAggregateType());
        summary.put("aggregateId", event.getAggregateId());
        summary.put("payloadPreview", event.getPayloadJson());
        log.setDeliverySummary(JsonUtils.toJson(summary));
        log.setErrorMessage(errorMessage);
        log.setTraceId(event.getTraceId());
        eventDeliveryLogMapper.insert(log);
    }
}
