package com.example.batch.worker.core.infrastructure;

import com.example.batch.common.utils.FileStateMachine;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.worker.core.domain.PipelineStepDefinition;
import com.example.batch.worker.core.domain.PipelineStepTemplate;
import com.example.batch.worker.core.mapper.PlatformFileRuntimeMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class PlatformFileRuntimeRepository {

  private final PlatformFileRuntimeMapper platformFileRuntimeMapper;

  public Map<String, Object> loadFileRecord(String tenantId, Long fileId) {
    if (!StringUtils.hasText(tenantId) || fileId == null) {
      return Map.of();
    }
    Map<String, Object> fileRecord =
        platformFileRuntimeMapper.selectFileRecord(params("tenantId", tenantId, "fileId", fileId));
    return fileRecord == null ? Map.of() : fileRecord;
  }

  public boolean existsFileRecordByStoragePath(
      String tenantId, String storageBucket, String storagePath) {
    if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(storagePath)) {
      return false;
    }
    Long count =
        platformFileRuntimeMapper.countFileRecordByStoragePath(
            params(
                "tenantId", tenantId, "storageBucket", storageBucket, "storagePath", storagePath));
    return count != null && count > 0;
  }

  public Map<String, Object> loadFileRecordByStoragePath(
      String tenantId, String storageBucket, String storagePath) {
    if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(storagePath)) {
      return Map.of();
    }
    Map<String, Object> row =
        platformFileRuntimeMapper.selectFileRecordByStoragePath(
            params(
                "tenantId", tenantId, "storageBucket", storageBucket, "storagePath", storagePath));
    return row == null ? Map.of() : row;
  }

  public Map<String, Object> loadLatestTemplateConfig(
      String tenantId, String templateCode, String templateType) {
    if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(templateCode)) {
      return Map.of();
    }
    Map<String, Object> templateConfig =
        platformFileRuntimeMapper.selectLatestTemplateConfig(
            params(
                "tenantId", tenantId, "templateCode", templateCode, "templateType", templateType));
    return templateConfig == null ? Map.of() : templateConfig;
  }

  public Map<String, Object> loadChannelConfig(String tenantId, String channelCode) {
    if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(channelCode)) {
      return Map.of();
    }
    Map<String, Object> channelConfig =
        platformFileRuntimeMapper.selectChannelConfig(
            params("tenantId", tenantId, "channelCode", channelCode));
    return channelConfig == null ? Map.of() : channelConfig;
  }

  public Long ensurePipelineDefinition(
      String tenantId,
      String jobCode,
      String pipelineType,
      String workerGroup,
      String description,
      List<PipelineStepTemplate> defaultSteps) {
    if (!StringUtils.hasText(tenantId)
        || !StringUtils.hasText(jobCode)
        || !StringUtils.hasText(pipelineType)) {
      return null;
    }
    Long pipelineDefinitionId =
        platformFileRuntimeMapper.selectLatestPipelineDefinitionId(
            params("tenantId", tenantId, "jobCode", jobCode));
    if (pipelineDefinitionId == null) {
      Map<String, Object> paramMap =
          params(
              "tenantId", tenantId,
              "jobCode", jobCode,
              "pipelineName", jobCode,
              "pipelineType", pipelineType,
              "workerGroup", workerGroup,
              "description", description);
      platformFileRuntimeMapper.insertPipelineDefinition(paramMap);
      pipelineDefinitionId = toLong(paramMap.get("id"));
    }
    ensurePipelineStepDefinitions(pipelineDefinitionId, defaultSteps);
    return pipelineDefinitionId;
  }

  public List<PipelineStepDefinition> loadPipelineSteps(Long pipelineDefinitionId) {
    if (pipelineDefinitionId == null) {
      return List.of();
    }
    List<Map<String, Object>> rows =
        platformFileRuntimeMapper.selectPipelineStepDefinitions(
            params("pipelineDefinitionId", pipelineDefinitionId, "enabledOnly", true));
    return mapPipelineStepDefinitions(rows);
  }

  public record CreatePipelineInstanceParam(
      String tenantId,
      Long pipelineDefinitionId,
      String jobCode,
      String pipelineType,
      Long fileId,
      Long relatedJobInstanceId,
      String currentStage,
      String traceId) {}

  public Long createPipelineInstance(CreatePipelineInstanceParam p) {
    if (!StringUtils.hasText(p.tenantId()) || p.pipelineDefinitionId() == null) {
      return null;
    }
    Map<String, Object> paramMap =
        params(
            "tenantId", p.tenantId(),
            "pipelineDefinitionId", p.pipelineDefinitionId(),
            "jobCode", p.jobCode(),
            "pipelineType", p.pipelineType(),
            "fileId", p.fileId(),
            "relatedJobInstanceId", p.relatedJobInstanceId(),
            "currentStage", p.currentStage(),
            "traceId", p.traceId(),
            "runStatus", com.example.batch.common.enums.PipelineRunStatus.RUNNING.name());
    platformFileRuntimeMapper.insertPipelineInstance(paramMap);
    return toLong(paramMap.get("id"));
  }

  public void bindFileToPipelineInstance(Long pipelineInstanceId, Long fileId) {
    if (pipelineInstanceId == null || fileId == null) {
      return;
    }
    platformFileRuntimeMapper.bindFileToPipelineInstance(
        params("pipelineInstanceId", pipelineInstanceId, "fileId", fileId));
  }

  public void updatePipelineStage(
      Long pipelineInstanceId, String currentStage, String lastSuccessStage) {
    if (pipelineInstanceId == null) {
      return;
    }
    platformFileRuntimeMapper.updatePipelineStage(
        params(
            "pipelineInstanceId",
            pipelineInstanceId,
            "currentStage",
            currentStage,
            "lastSuccessStage",
            lastSuccessStage));
  }

  public void markPipelineSuccess(
      Long pipelineInstanceId, String currentStage, String lastSuccessStage) {
    if (pipelineInstanceId == null) {
      return;
    }
    platformFileRuntimeMapper.markPipelineSuccess(
        params(
            "pipelineInstanceId",
            pipelineInstanceId,
            "currentStage",
            currentStage,
            "lastSuccessStage",
            lastSuccessStage,
            "runStatus",
            com.example.batch.common.enums.PipelineRunStatus.SUCCESS.name()));
  }

  public void markPipelineFailed(
      Long pipelineInstanceId, String currentStage, String lastSuccessStage) {
    if (pipelineInstanceId == null) {
      return;
    }
    platformFileRuntimeMapper.markPipelineFailed(
        params(
            "pipelineInstanceId",
            pipelineInstanceId,
            "currentStage",
            currentStage,
            "lastSuccessStage",
            lastSuccessStage,
            "runStatus",
            com.example.batch.common.enums.PipelineRunStatus.FAILED.name()));
  }

  public Long startStepRun(
      Long pipelineInstanceId, String stepCode, String stageCode, Object inputSummary) {
    if (pipelineInstanceId == null
        || !StringUtils.hasText(stepCode)
        || !StringUtils.hasText(stageCode)) {
      return null;
    }
    Integer nextExecutionSeq =
        platformFileRuntimeMapper.selectNextStepRunSeq(
            params("pipelineInstanceId", pipelineInstanceId, "stepCode", stepCode));
    Map<String, Object> paramMap =
        params(
            "pipelineInstanceId",
            pipelineInstanceId,
            "stepCode",
            stepCode,
            "stageCode",
            stageCode,
            "runSeq",
            nextExecutionSeq == null ? 1 : nextExecutionSeq,
            "stepStatus",
            com.example.batch.common.enums.PipelineRunStatus.RUNNING.name(),
            "inputSummaryJson",
            toJson(inputSummary));
    platformFileRuntimeMapper.insertStepRun(paramMap);
    return toLong(paramMap.get("id"));
  }

  public void finishStepRunSuccess(Long stepRunId, Object outputSummary) {
    finishStepRun(stepRunId, "SUCCESS", null, null, outputSummary);
  }

  public void finishStepRunFailure(
      Long stepRunId, String errorCode, String errorMessage, Object outputSummary) {
    finishStepRun(stepRunId, "FAILED", errorCode, errorMessage, outputSummary);
  }

  private void finishStepRun(
      Long stepRunId, String status, String errorCode, String errorMessage, Object outputSummary) {
    if (stepRunId == null) {
      return;
    }
    platformFileRuntimeMapper.finishStepRun(
        params(
            "stepRunId", stepRunId,
            "status", status,
            "outputSummaryJson", toJson(outputSummary),
            "errorCode", errorCode,
            "errorMessage", truncate(errorMessage, 1024)));
  }

  @Transactional
  public Long createFileRecord(FileRecordParam p) {
    String tenantId = p.getTenantId();
    String fileCode = p.getFileCode();
    String bizType = p.getBizType();
    String fileCategory = p.getFileCategory();
    String fileName = p.getFileName();
    String originalFileName = p.getOriginalFileName();
    String fileFormatType = p.getFileFormatType();
    String charset = p.getCharset();
    long fileSizeBytes = p.getFileSizeBytes();
    String checksumType = p.getChecksumType();
    String checksumValue = p.getChecksumValue();
    String storageType = p.getStorageType();
    String storagePath = p.getStoragePath();
    String storageBucket = p.getStorageBucket();
    String fileVersion = p.getFileVersion();
    LocalDate bizDate = p.getBizDate();
    String sourceType = p.getSourceType();
    String sourceRef = p.getSourceRef();
    String fileStatus = p.getFileStatus();
    String traceId = p.getTraceId();
    Object metadata = p.getMetadata();
    if (!StringUtils.hasText(tenantId)
        || !StringUtils.hasText(fileCategory)
        || !StringUtils.hasText(fileName)
        || !StringUtils.hasText(fileFormatType)
        || !StringUtils.hasText(storageType)
        || !StringUtils.hasText(storagePath)
        || !StringUtils.hasText(sourceType)
        || !StringUtils.hasText(fileStatus)) {
      return null;
    }
    FileStateMachine.assertInitialStatus(fileStatus);
    int nextGenerationNo = 1;
    if (StringUtils.hasText(fileCode)) {
      Integer maxGeneration =
          platformFileRuntimeMapper.selectMaxFileGenerationNo(
              params("tenantId", tenantId, "fileCode", fileCode));
      nextGenerationNo = (maxGeneration == null ? 0 : maxGeneration) + 1;
      platformFileRuntimeMapper.markHistoricalFileNotLatest(
          params("tenantId", tenantId, "fileCode", fileCode));
    }
    final int finalNextGenerationNo = nextGenerationNo;
    String resolvedFileVersion =
        StringUtils.hasText(fileVersion) ? fileVersion : "v" + finalNextGenerationNo;
    Map<String, Object> paramMap =
        params(
            "tenantId", tenantId,
            "fileCode", fileCode,
            "bizType", bizType,
            "fileCategory", fileCategory,
            "fileName", fileName,
            "originalFileName", originalFileName,
            "fileExt", resolveFileExt(fileName),
            "fileFormatType", fileFormatType,
            "charset", charset,
            "mimeType", resolveMimeType(fileFormatType),
            "fileSizeBytes", Math.max(fileSizeBytes, 0L),
            "checksumType", defaultText(checksumType, "NONE"),
            "checksumValue", checksumValue,
            "storageType", storageType,
            "storagePath", storagePath,
            "storageBucket", storageBucket,
            "fileVersion", resolvedFileVersion,
            "fileGenerationNo", finalNextGenerationNo,
            "sourceType", sourceType,
            "sourceRef", sourceRef,
            "fileStatus", fileStatus,
            "bizDate", bizDate,
            "traceId", traceId,
            "metadataJson", toJson(metadata));
    platformFileRuntimeMapper.insertFileRecord(paramMap);
    return toLong(paramMap.get("id"));
  }

  public void updateFileStatus(Long fileId, String fileStatus, Object metadata) {
    if (fileId == null || !StringUtils.hasText(fileStatus)) {
      return;
    }
    String currentStatus = platformFileRuntimeMapper.selectFileStatus(params("fileId", fileId));
    if (!StringUtils.hasText(currentStatus)) {
      return;
    }
    FileStateMachine.assertTransition(currentStatus, fileStatus);
    platformFileRuntimeMapper.updateFileRecordStatus(
        params("fileId", fileId, "fileStatus", fileStatus, "metadataJson", toJson(metadata)));
  }

  public void updateFileMetadata(Long fileId, Object metadata) {
    if (fileId == null) {
      return;
    }
    platformFileRuntimeMapper.updateFileRecordMetadata(
        params("fileId", fileId, "metadataJson", toJson(metadata)));
  }

  public Long insertFileErrorRecord(FileErrorRecordParam p) {
    Map<String, Object> paramMap =
        params(
            "tenantId", p.getTenantId(),
            "fileId", p.getFileId(),
            "pipelineInstanceId", p.getPipelineInstanceId(),
            "pipelineStepRunId", p.getPipelineStepRunId(),
            "recordNo", p.getRecordNo(),
            "errorCode", p.getErrorCode(),
            "errorMessage", truncate(p.getErrorMessage(), 1024),
            "errorStage", p.getErrorStage(),
            "isSkipped", p.isSkipped(),
            "skipAction", p.getSkipAction(),
            "rawRecordJson", toJson(p.getRawRecord()));
    platformFileRuntimeMapper.insertFileErrorRecord(paramMap);
    return toLong(paramMap.get("id"));
  }

  public List<Map<String, Object>> loadFileErrorRecords(
      String tenantId, Long fileId, String errorCode, String errorStage, int limit) {
    if (!StringUtils.hasText(tenantId) || limit <= 0) {
      return List.of();
    }
    return platformFileRuntimeMapper.selectFileErrorRecords(
        params(
            "tenantId", tenantId,
            "fileId", fileId,
            "errorCode", errorCode,
            "errorStage", errorStage,
            "limit", limit));
  }

  public void appendAudit(FileAuditParam p) {
    if (p.getFileId() == null
        || !StringUtils.hasText(p.getTenantId())
        || !StringUtils.hasText(p.getOperationType())
        || !StringUtils.hasText(p.getOperationResult())) {
      return;
    }
    platformFileRuntimeMapper.insertFileAuditLog(
        params(
            "tenantId", p.getTenantId(),
            "fileId", p.getFileId(),
            "operationType", p.getOperationType(),
            "operationResult", p.getOperationResult(),
            "operatorType", defaultText(p.getOperatorType(), "SYSTEM"),
            "operatorId", p.getOperatorId(),
            "traceId", p.getTraceId(),
            "evidenceRef", p.getEvidenceRef(),
            "detailSummaryJson", toJson(p.getDetailSummary())));
  }

  public Long toLong(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String string && !string.isBlank()) {
      return Long.valueOf(string);
    }
    return null;
  }

  public Instant toInstant(Object value) {
    if (value instanceof Timestamp timestamp) {
      return timestamp.toInstant();
    }
    return null;
  }

  private String toJson(Object value) {
    return value == null ? null : JsonUtils.toJson(value);
  }

  private void ensurePipelineStepDefinitions(
      Long pipelineDefinitionId, List<PipelineStepTemplate> defaultSteps) {
    if (pipelineDefinitionId == null || defaultSteps == null || defaultSteps.isEmpty()) {
      return;
    }
    List<PipelineStepDefinition> existingSteps =
        mapPipelineStepDefinitions(
            platformFileRuntimeMapper.selectPipelineStepDefinitions(
                params("pipelineDefinitionId", pipelineDefinitionId, "enabledOnly", false)));
    Set<String> existingCodes = new HashSet<>();
    for (PipelineStepDefinition existingStep : existingSteps) {
      existingCodes.add(existingStep.stepCode());
    }
    for (PipelineStepTemplate template : defaultSteps) {
      if (shouldSkipStepTemplate(template, existingCodes)) {
        continue;
      }
      platformFileRuntimeMapper.insertPipelineStepDefinition(
          params(
              "pipelineDefinitionId", pipelineDefinitionId,
              "stepCode", template.stepCode(),
              "stepName", defaultText(template.stepName(), template.stepCode()),
              "stageCode", template.stageCode(),
              "stepOrder", template.stepOrder() == null ? 0 : template.stepOrder(),
              "implCode", template.implCode(),
              "stepParamsJson", toJson(template.stepParams()),
              "timeoutSeconds", template.timeoutSeconds() == null ? 0 : template.timeoutSeconds(),
              "retryPolicy", defaultText(template.retryPolicy(), "NONE"),
              "retryMaxCount", template.retryMaxCount() == null ? 0 : template.retryMaxCount(),
              "enabled", template.enabled()));
    }
  }

  private List<PipelineStepDefinition> mapPipelineStepDefinitions(List<Map<String, Object>> rows) {
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    List<PipelineStepDefinition> definitions = new ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) {
      definitions.add(
          new PipelineStepDefinition(
              toLong(row.get("id")),
              toLong(row.get("pipeline_definition_id")),
              stringValue(row.get("step_code")),
              stringValue(row.get("step_name")),
              stringValue(row.get("stage_code")),
              toInteger(row.get("step_order")),
              stringValue(row.get("impl_code")),
              toMap(row.get("step_params")),
              toInteger(row.get("timeout_seconds")),
              stringValue(row.get("retry_policy")),
              toInteger(row.get("retry_max_count")),
              Boolean.TRUE.equals(row.get("enabled"))));
    }
    return Collections.unmodifiableList(definitions);
  }

  private Integer toInteger(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String string && !string.isBlank()) {
      return Integer.valueOf(string);
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> toMap(Object value) {
    if (value == null) {
      return Map.of();
    }
    if (value instanceof Map<?, ?> rawMap) {
      Map<String, Object> mapped = new LinkedHashMap<>();
      rawMap.forEach((key, rawValue) -> mapped.put(String.valueOf(key), rawValue));
      return mapped;
    }
    return JsonUtils.fromJson(String.valueOf(value), Map.class);
  }

  private String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private String resolveFileExt(String fileName) {
    if (!StringUtils.hasText(fileName) || !fileName.contains(".")) {
      return null;
    }
    return fileName.substring(fileName.lastIndexOf('.') + 1);
  }

  private String resolveMimeType(String fileFormatType) {
    if (!StringUtils.hasText(fileFormatType)) {
      return "application/octet-stream";
    }
    return switch (fileFormatType) {
      case "JSON" -> "application/json";
      case "XML" -> "application/xml";
      case "EXCEL" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
      default -> "text/plain";
    };
  }

  private boolean shouldSkipStepTemplate(PipelineStepTemplate template, Set<String> existingCodes) {
    return template == null
        || !StringUtils.hasText(template.stepCode())
        || existingCodes.contains(template.stepCode());
  }

  private String defaultText(String value, String fallback) {
    return StringUtils.hasText(value) ? value : fallback;
  }

  private Map<String, Object> params(Object... pairs) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      values.put(String.valueOf(pairs[index]), pairs[index + 1]);
    }
    return values;
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }
}
