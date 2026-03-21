package com.example.batch.orchestrator.application.engine;

import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;

public interface OutboxPublisher {

    boolean publish(OutboxEventEntity event);
}
