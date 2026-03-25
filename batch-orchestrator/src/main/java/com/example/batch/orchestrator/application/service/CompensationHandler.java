package com.example.batch.orchestrator.application.service;

import com.example.batch.orchestrator.domain.command.CompensationSubmitCommand;
import com.example.batch.orchestrator.domain.entity.CompensationCommandEntity;
import java.util.Map;

/**
 * Strategy interface for a single compensation type.
 *
 * <p>Each implementation handles one {@code compensationType} value (JOB / STEP / PARTITION /
 * FILE / BATCH / DLQ). {@link DefaultCompensationService} routes to the correct handler via a
 * Map&lt;String, CompensationHandler&gt; built at construction time, eliminating the original
 * 6-way switch.
 */
@FunctionalInterface
public interface CompensationHandler {

    Map<String, Object> handle(CompensationSubmitCommand command,
                               String commandNo,
                               String traceId,
                               CompensationCommandEntity entity);
}
