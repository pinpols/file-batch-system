package io.github.pinpols.batch.console.domain.ops.application.contract.response;

import io.github.pinpols.batch.console.domain.ops.view.OutboxEventStatusStat;
import java.util.List;

public record ConsoleOutboxStatsResponse(
    String tenantId, List<OutboxEventStatusStat> statusBreakdown) {}
