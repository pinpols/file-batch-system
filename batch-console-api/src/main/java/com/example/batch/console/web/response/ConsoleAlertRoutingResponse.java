package com.example.batch.console.web.response;

import java.time.Instant;

public record ConsoleAlertRoutingResponse(
        Long id,
        String tenantId,
        String routeCode,
        String routeName,
        String team,
        String alertGroup,
        String severity,
        String receiver,
        String groupBy,
        Integer groupWaitSeconds,
        Integer groupIntervalSeconds,
        Integer repeatIntervalSeconds,
        Boolean enabled,
        String description,
        Instant createdAt,
        Instant updatedAt) {}
