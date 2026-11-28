package io.github.pinpols.batch.console.domain.ops.application.contract.response;

import java.util.List;

import io.github.pinpols.batch.console.domain.ops.view.OutboxEventStatusStat;

public record ConsoleOutboxStatsResponse(String tenantId, List<OutboxEventStatusStat> statusBreakdown) {}
