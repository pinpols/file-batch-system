package com.example.batch.console.web.response.ops;

import java.util.List;
import java.util.Map;

public record ConsoleOutboxStatsResponse(
    String tenantId, List<Map<String, Object>> statusBreakdown) {}
