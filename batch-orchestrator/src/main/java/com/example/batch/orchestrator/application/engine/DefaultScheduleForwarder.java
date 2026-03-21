package com.example.batch.orchestrator.application.engine;

import com.example.batch.orchestrator.config.OutboxProperties;
import com.example.batch.orchestrator.application.plan.SchedulePlan;
import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.domain.query.OutboxEventQuery;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultScheduleForwarder implements ScheduleForwarder {

    private final OutboxEventMapper outboxEventMapper;
    private final OutboxPublisher outboxPublisher;
    private final OutboxProperties outboxProperties;

    public DefaultScheduleForwarder(OutboxEventMapper outboxEventMapper,
                                    OutboxPublisher outboxPublisher,
                                    OutboxProperties outboxProperties) {
        this.outboxEventMapper = outboxEventMapper;
        this.outboxPublisher = outboxPublisher;
        this.outboxProperties = outboxProperties;
    }

    @Override
    @Transactional
    public void advance(SchedulePlan plan) {
        List<OutboxEventEntity> pendingEvents = outboxEventMapper.selectPending(new OutboxEventQuery(
                plan == null ? null : plan.getTenantId(),
                null,
                null,
                null
        ));
        pendingEvents.stream()
                .limit(outboxProperties.getBatchSize())
                .forEach(event -> {
                    if (outboxEventMapper.markPublishing(event.getTenantId(), event.getId()) > 0) {
                        boolean published = outboxPublisher.publish(event);
                        if (published) {
                            outboxEventMapper.markPublished(event.getTenantId(), event.getId());
                        } else {
                            outboxEventMapper.markFailed(
                                    event.getTenantId(),
                                    event.getId(),
                                    Instant.now().plusSeconds(outboxProperties.getRetryDelaySeconds())
                            );
                        }
                    }
                });
    }
}
