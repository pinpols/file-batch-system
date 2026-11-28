package io.github.pinpols.batch.orchestrator.service;

import java.time.LocalDate;

import lombok.Builder;

import io.github.pinpols.batch.orchestrator.service.BatchDayOperationService.BatchDayOperation;

@Builder
public record BatchDayOperateCommand(
        String tenantId,
        String calendarCode,
        LocalDate bizDate,
        BatchDayOperation action,
        String operatorId,
        String reason) {}
