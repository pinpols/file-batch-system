package com.example.batch.orchestrator.application.engine;

import com.example.batch.orchestrator.config.OutboxProperties;
import com.example.batch.orchestrator.domain.entity.EventOutboxRetryEntity;
import com.example.batch.orchestrator.application.plan.SchedulePlan;
import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.domain.query.OutboxEventQuery;
import com.example.batch.orchestrator.mapper.EventOutboxRetryMapper;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DefaultScheduleForwarder implements ScheduleForwarder {

    private final OutboxEventMapper outboxEventMapper;
    private final EventOutboxRetryMapper eventOutboxRetryMapper;
    private final OutboxPublisher outboxPublisher;
    private final OutboxProperties outboxProperties;

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
                            Instant nextRetryAt = Instant.now().plusSeconds(outboxProperties.getRetryDelaySeconds());
                            int attemptNo = event.getPublishAttempt() == null ? 1 : event.getPublishAttempt() + 1;
                            if (attemptNo >= outboxProperties.getMaxRetryAttempts()) {
                                outboxEventMapper.markGiveUp(event.getTenantId(), event.getId());
                                recordRetry(event, attemptNo, null, "retry attempts exhausted");
                            } else {
                                outboxEventMapper.markFailed(
                                        event.getTenantId(),
                                        event.getId(),
                                        nextRetryAt
                                );
                                recordRetry(event, attemptNo, nextRetryAt, "publish failed");
                            }
                        }
                    }
                });
    }

    private void recordRetry(OutboxEventEntity event,
                             int attemptNo,
                             Instant nextRetryAt,
                             String reason) {
        EventOutboxRetryEntity retry = new EventOutboxRetryEntity();
        retry.setTenantId(event.getTenantId());
        retry.setOutboxEventId(event.getId());
        retry.setEventKey(event.getEventKey());
        retry.setRetryAttempt(attemptNo);
        retry.setRetryStatus(nextRetryAt == null
                ? com.example.batch.common.enums.RetryScheduleStatus.EXHAUSTED.code()
                : com.example.batch.common.enums.RetryScheduleStatus.FAILED.code());
        retry.setRetryReason(reason);
        retry.setNextRetryAt(nextRetryAt);
        retry.setTraceId(event.getTraceId());
        eventOutboxRetryMapper.insert(retry);
    }
}
