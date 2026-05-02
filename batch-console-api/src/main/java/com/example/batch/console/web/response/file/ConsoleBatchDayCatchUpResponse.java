package com.example.batch.console.web.response.file;

import java.util.List;

public record ConsoleBatchDayCatchUpResponse(
    String tenantId,
    String calendarCode,
    String bizDate,
    String catchUpPolicy,
    List<ConsoleBatchDayCatchUpItemResponse> items) {}
