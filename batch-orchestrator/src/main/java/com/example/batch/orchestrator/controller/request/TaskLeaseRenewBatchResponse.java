package com.example.batch.orchestrator.controller.request;

import java.util.List;

public record TaskLeaseRenewBatchResponse(List<TaskLeaseRenewResultPayload> results) {}
