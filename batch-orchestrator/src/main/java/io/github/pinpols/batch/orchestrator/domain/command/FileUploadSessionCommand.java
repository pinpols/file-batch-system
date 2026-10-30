package io.github.pinpols.batch.orchestrator.domain.command;

import java.time.LocalDate;
import java.util.List;
import lombok.Builder;

@Builder
public record FileUploadSessionCommand(
    String tenantId,
    String channelCode,
    String fileName,
    String operatorId,
    String traceId,
    String calendarCode,
    Long workflowDefinitionId,
    String fileGroupCode,
    LocalDate bizDate,
    List<String> requiredFileNames,
    String bundleTemplateCode) {}
