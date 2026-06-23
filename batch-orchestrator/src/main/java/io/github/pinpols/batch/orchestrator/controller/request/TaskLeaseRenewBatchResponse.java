package io.github.pinpols.batch.orchestrator.controller.request;

import java.util.List;

public record TaskLeaseRenewBatchResponse(List<TaskLeaseRenewResultPayload> results) {}
