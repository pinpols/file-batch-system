package io.github.pinpols.batch.orchestrator.controller.request;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskLeaseRenewBatchRequest(List<TaskLeaseRenewItemPayload> items) {}
