package io.github.pinpols.batch.console.domain.ops.application.contract.response;

import java.util.List;
import java.util.Map;

public record ConsoleOutboxStatsResponse(
    String tenantId, List<Map<String, Object>> statusBreakdown) {}
