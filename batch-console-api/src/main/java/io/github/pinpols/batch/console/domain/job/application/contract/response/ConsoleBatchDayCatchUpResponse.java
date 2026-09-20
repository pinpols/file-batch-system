package io.github.pinpols.batch.console.domain.job.application.contract.response;

import java.util.List;

public record ConsoleBatchDayCatchUpResponse(
    String tenantId,
    String calendarCode,
    String bizDate,
    String catchUpPolicy,
    List<ConsoleBatchDayCatchUpItemResponse> items) {}
