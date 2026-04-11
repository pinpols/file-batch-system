package com.example.batch.console.web.response;

import java.util.List;

public record ConsoleBatchDayCatchUpResponse(
        String tenantId,
        String calendarCode,
        String bizDate,
        String catchUpPolicy,
        List<ConsoleBatchDayCatchUpItemResponse> items) {}
