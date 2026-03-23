package com.example.batch.e2e.support;

import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.model.PageRequest;
import com.example.batch.orchestrator.application.engine.OutboxPublisher;
import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.domain.query.OutboxEventQuery;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Publishes pending outbox rows to Kafka (integration tests disable the forwarder scheduler).
 */
@Component
public class E2eOutboxPublishSupport {

    private final OutboxPublisher outboxPublisher;
    private final OutboxEventMapper outboxEventMapper;

    public E2eOutboxPublishSupport(OutboxPublisher outboxPublisher, OutboxEventMapper outboxEventMapper) {
        this.outboxPublisher = outboxPublisher;
        this.outboxEventMapper = outboxEventMapper;
    }

    public void publishAllPending(String tenantId) {
        List<OutboxEventEntity> pending = outboxEventMapper.selectPending(
                new OutboxEventQuery(tenantId, null, null, new PageRequest(1, 500),
                        OutboxPublishStatus.NEW.code(), OutboxPublishStatus.FAILED.code()));
        for (OutboxEventEntity event : pending) {
            outboxPublisher.publish(event);
        }
    }
}
