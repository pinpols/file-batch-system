package com.example.batch.orchestrator.controller.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskLeaseRenewBatchRequest(List<TaskLeaseRenewItemPayload> items) {}
