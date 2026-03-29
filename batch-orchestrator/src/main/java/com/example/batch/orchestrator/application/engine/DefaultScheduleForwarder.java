package com.example.batch.orchestrator.application.engine;

import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.logging.BatchMdc;
import com.example.batch.common.logging.StructuredLogField;
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
    public ScheduleForwarderResult advance(SchedulePlan plan) {
        List<OutboxEventEntity> pendingEvents = outboxEventMapper.selectPending(new OutboxEventQuery(
                plan == null ? null : plan.getTenantId(),
                null,
                null,
                null,
                OutboxPublishStatus.NEW.code(),
                OutboxPublishStatus.FAILED.code()
        ));
        int attemptedEvents = 0;
        int publishSucceeded = 0;
        int publishFailed = 0;

        for (OutboxEventEntity event : pendingEvents.stream().limit(outboxProperties.getBatchSize()).toList()) {
            // Ensure outbox forwarding logs can be correlated by traceId.
            BatchMdc.put(StructuredLogField.TENANT_ID, event.getTenantId());
            BatchMdc.put(StructuredLogField.TRACE_ID, event.getTraceId());
            if (outboxEventMapper.markPublishing(
                    event.getTenantId(),
                    event.getId(),
                    OutboxPublishStatus.PUBLISHING.code(),
                    OutboxPublishStatus.NEW.code(),
                    OutboxPublishStatus.FAILED.code()) > 0) {
                try {
                    attemptedEvents++;
                    boolean published = outboxPublisher.publish(event);
                    if (published) {
                        publishSucceeded++;
                        outboxEventMapper.markPublished(event.getTenantId(), event.getId(), OutboxPublishStatus.PUBLISHED.code());
                    } else {
                        publishFailed++;
                        Instant nextRetryAt = Instant.now().plusSeconds(outboxProperties.getRetryDelaySeconds());
                        int publishAttemptNo = event.getPublishAttempt() == null ? 1 : event.getPublishAttempt() + 1;
                        if (publishAttemptNo >= outboxProperties.getMaxRetryAttempts()) {
                            outboxEventMapper.markGiveUp(event.getTenantId(), event.getId(), OutboxPublishStatus.GIVE_UP.code());
                            recordRetry(event, publishAttemptNo, null, "retry attempts exhausted");
                        } else {
                            outboxEventMapper.markFailed(
                                    event.getTenantId(),
                                    event.getId(),
                                    OutboxPublishStatus.FAILED.code(),
                                    nextRetryAt
                            );
                            recordRetry(event, publishAttemptNo, nextRetryAt, "publish failed");
                        }
                    }
                } finally {
                    // Clear MDC so later events won't accidentally reuse fields.
                    BatchMdc.remove(StructuredLogField.TENANT_ID);
                    BatchMdc.remove(StructuredLogField.TRACE_ID);
                }
            }
        }
        return ScheduleForwarderResult.of(attemptedEvents, publishSucceeded, publishFailed);
    }

    /**
     * Persist outbox publish retry attempts separately from business retry counters.
     */
    private void recordRetry(OutboxEventEntity event,
                             int publishAttemptNo,
                             Instant nextRetryAt,
                             String reason) {
        EventOutboxRetryEntity retry = new EventOutboxRetryEntity();
        retry.setTenantId(event.getTenantId());
        retry.setOutboxEventId(event.getId());
        retry.setEventKey(event.getEventKey());
        retry.setPublishAttempt(publishAttemptNo);
        retry.setRetryStatus(nextRetryAt == null
                ? com.example.batch.common.enums.RetryScheduleStatus.EXHAUSTED.code()
                : com.example.batch.common.enums.RetryScheduleStatus.FAILED.code());
        retry.setRetryReason(reason);
        retry.setNextRetryAt(nextRetryAt);
        retry.setTraceId(event.getTraceId());
        eventOutboxRetryMapper.insert(retry);
    }
}
