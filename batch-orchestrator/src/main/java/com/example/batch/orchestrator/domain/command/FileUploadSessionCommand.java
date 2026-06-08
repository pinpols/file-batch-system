package com.example.batch.orchestrator.domain.command;

import lombok.Builder;

@Builder
public record FileUploadSessionCommand(
    String tenantId, String channelCode, String fileName, String operatorId, String traceId) {}
