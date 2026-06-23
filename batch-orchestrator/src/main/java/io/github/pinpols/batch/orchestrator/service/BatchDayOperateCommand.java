package io.github.pinpols.batch.orchestrator.service;

import io.github.pinpols.batch.orchestrator.service.BatchDayOperationService.BatchDayOperation;
import java.time.LocalDate;
import lombok.Builder;

@Builder
public record BatchDayOperateCommand(
    String tenantId,
    String calendarCode,
    LocalDate bizDate,
    BatchDayOperation action,
    String operatorId,
    String reason) {}
