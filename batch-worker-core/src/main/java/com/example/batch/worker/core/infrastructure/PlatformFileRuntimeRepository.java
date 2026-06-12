package com.example.batch.worker.core.infrastructure;

import com.example.batch.common.enums.PipelineRunStatus;
import com.example.batch.common.utils.FileStateMachine;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
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
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 所有 Worker 模块的核心数据访问层，负责 file_record、pipeline 实例、步骤运行及审计的全生命周期管理。
 *
 * <p><b>状态安全</b>：每次调用 {@link #updateFileStatus} 前都经过 {@link FileStateMachine#assertTransition}
 * 校验，防止非法状态跃迁； {@link #createFileRecord} 调用 {@link FileStateMachine#assertInitialStatus} 验证初始状态。
 *
 * <p><b>Pipeline 定义自动创建</b>：{@link #ensurePipelineDefinition} 在 Worker 首次运行时 自动写入 pipeline
 * 定义及默认步骤（通过 {@code ensurePipelineStepDefinitions} 补全缺失步骤）， 后续运行直接复用已有定义，无需手工预置。
 *
 * <p><b>主要操作</b>：
 *
 * <ul>
 *   <li>{@code createFileRecord} / {@code updateFileStatus} / {@code updateFileMetadata} — 文件记录管理
 *   <li>{@code ensurePipelineDefinition} / {@code createPipelineInstance} — Pipeline 生命周期
 *   <li>{@code startStepRun} / {@code finishStepRun} — 步骤运行记录
 *   <li>{@code insertFileErrorRecord} — 坏记录落库
 *   <li>{@code appendAudit} — 审计日志
 * </ul>
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class PlatformFileRuntimeRepository {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String KEY_PIPELINE_DEFINITION_ID = "pipelineDefinitionId";
  private static final String KEY_CURRENT_STAGE = "currentStage";
  private static final String KEY_TENANT_ID = "tenantId";
  private static final String KEY_FILE_ID = "fileId";
  private static final String KEY_PIPELINE_INSTANCE_ID = "pipelineInstanceId";
  private static final String KEY_ID = "id";

  private final PlatformFileRuntimeMapper platformFileRuntimeMapper;

  public Map<String, Object> loadFileRecord(String tenantId, Long fileId) {
    if (!Texts.hasText(tenantId) || fileId == null) {
      return Map.of();
    }
    Map<String, Object> fileRecord =
        platformFileRuntimeMapper.selectFileRecord(
            params(KEY_TENANT_ID, tenantId, KEY_FILE_ID, fileId));
    return fileRecord == null ? Map.of() : fileRecord;
  }

  public boolean existsFileRecordByStoragePath(
      String tenantId, String storageBucket, String storagePath) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(storagePath)) {
      return false;
    }
    Long count =
        platformFileRuntimeMapper.countFileRecordByStoragePath(
            params(
                KEY_TENANT_ID,
                tenantId,
                "storageBucket",
                storageBucket,
                "storagePath",
                storagePath));
    return count != null && count > 0;
  }

  public Map<String, Object> loadFileRecordByStoragePath(
      String tenantId, String storageBucket, String storagePath) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(storagePath)) {
      return Map.of();
    }
    Map<String, Object> row =
        platformFileRuntimeMapper.selectFileRecordByStoragePath(
            params(
                KEY_TENANT_ID,
                tenantId,
                "storageBucket",
                storageBucket,
                "storagePath",
                storagePath));
    return row == null ? Map.of() : row;
  }

  public Map<String, Object> loadLatestTemplateConfig(
      String tenantId, String templateCode, String templateType) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(templateCode)) {
      return Map.of();
    }
    Map<String, Object> templateConfig =
        platformFileRuntimeMapper.selectLatestTemplateConfig(
            params(
                KEY_TENANT_ID,
                tenantId,
                "templateCode",
                templateCode,
                "templateType",
                templateType));
    return templateConfig == null ? Map.of() : templateConfig;
  }

  public Map<String, Object> loadChannelConfig(String tenantId, String channelCode) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(channelCode)) {
      return Map.of();
    }
    Map<String, Object> channelConfig =
        platformFileRuntimeMapper.selectChannelConfig(
            params(KEY_TENANT_ID, tenantId, "channelCode", channelCode));
    return channelConfig == null ? Map.of() : channelConfig;
  }

  public Long ensurePipelineDefinition(
      String tenantId,
      String jobCode,
      String pipelineType,
      String workerGroup,
      String description,
      List<PipelineStepTemplate> defaultSteps) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(jobCode) || !Texts.hasText(pipelineType)) {
      return null;
    }
    Long pipelineDefinitionId =
        platformFileRuntimeMapper.selectLatestPipelineDefinitionId(
            params(KEY_TENANT_ID, tenantId, "jobCode", jobCode));
    if (pipelineDefinitionId == null) {
      Map<String, Object> paramMap =
          params(
              KEY_TENANT_ID,
              tenantId,
              "jobCode",
              jobCode,
              "pipelineName",
              jobCode,
              "pipelineType",
              pipelineType,
              "workerGroup",
              workerGroup,
              "description",
              description);
      platformFileRuntimeMapper.insertPipelineDefinition(paramMap);
      pipelineDefinitionId = toLong(paramMap.get(KEY_ID));
      // 仅在首次创建 pipeline_definition 时写入 default steps。已存在的 pipeline
      // 不再追加：跨 worker 错位调用（如 EXPORT worker 因 sourcePayload 继承拿到
      // IMPORT 子作业的 jobCode）会把别的 worker 的 default steps 累积进来，导致
      // step_code 大杂烩，最终 worker 在 step registry 找不到 impl 报"找不到步骤实现"。
      ensurePipelineStepDefinitions(pipelineDefinitionId, defaultSteps);
    }
    return pipelineDefinitionId;
  }

  public List<PipelineStepDefinition> loadPipelineSteps(Long pipelineDefinitionId) {
    if (pipelineDefinitionId == null) {
      return List.of();
    }
    List<Map<String, Object>> rows =
        platformFileRuntimeMapper.selectPipelineStepDefinitions(
            params(KEY_PIPELINE_DEFINITION_ID, pipelineDefinitionId, "enabledOnly", true));
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
    if (!Texts.hasText(p.tenantId()) || p.pipelineDefinitionId() == null) {
      return null;
    }
    Map<String, Object> paramMap =
        params(
            KEY_TENANT_ID,
            p.tenantId(),
            KEY_PIPELINE_DEFINITION_ID,
            p.pipelineDefinitionId(),
            "jobCode",
            p.jobCode(),
            "pipelineType",
            p.pipelineType(),
            KEY_FILE_ID,
            p.fileId(),
            "relatedJobInstanceId",
            p.relatedJobInstanceId(),
            KEY_CURRENT_STAGE,
            p.currentStage(),
            "traceId",
            p.traceId(),
            "runStatus",
            PipelineRunStatus.RUNNING.name());
    platformFileRuntimeMapper.insertPipelineInstance(paramMap);
    return toLong(paramMap.get(KEY_ID));
  }

  public void bindFileToPipelineInstance(Long pipelineInstanceId, Long fileId) {
    if (pipelineInstanceId == null || fileId == null) {
      return;
    }
    platformFileRuntimeMapper.bindFileToPipelineInstance(
        params(KEY_PIPELINE_INSTANCE_ID, pipelineInstanceId, KEY_FILE_ID, fileId));
  }

  public void updatePipelineStage(
      Long pipelineInstanceId, String currentStage, String lastSuccessStage) {
    if (pipelineInstanceId == null) {
      return;
    }
    platformFileRuntimeMapper.updatePipelineStage(
        params(
            KEY_PIPELINE_INSTANCE_ID,
            pipelineInstanceId,
            KEY_CURRENT_STAGE,
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
            KEY_PIPELINE_INSTANCE_ID,
            pipelineInstanceId,
            KEY_CURRENT_STAGE,
            currentStage,
            "lastSuccessStage",
            lastSuccessStage,
            "runStatus",
            PipelineRunStatus.SUCCESS.name()));
  }

  public void markPipelineFailed(
      Long pipelineInstanceId, String currentStage, String lastSuccessStage) {
    if (pipelineInstanceId == null) {
      return;
    }
    platformFileRuntimeMapper.markPipelineFailed(
        params(
            KEY_PIPELINE_INSTANCE_ID,
            pipelineInstanceId,
            KEY_CURRENT_STAGE,
            currentStage,
            "lastSuccessStage",
            lastSuccessStage,
            "runStatus",
            PipelineRunStatus.FAILED.name()));
  }

  public Long startStepRun(
      String tenantId,
      Long pipelineInstanceId,
      String stepCode,
      String stageCode,
      Object inputSummary) {
    if (!Texts.hasText(tenantId)
        || pipelineInstanceId == null
        || !Texts.hasText(stepCode)
        || !Texts.hasText(stageCode)) {
      return null;
    }
    Map<String, Object> paramMap =
        params(
            KEY_TENANT_ID,
            tenantId,
            KEY_PIPELINE_INSTANCE_ID,
            pipelineInstanceId,
            "stepCode",
            stepCode,
            "stageCode",
            stageCode,
            "stepStatus",
            PipelineRunStatus.RUNNING.name(),
            "inputSummaryJson",
            toJson(inputSummary));
    for (int i = 0; i < 5; i++) {
      try {
        // Citus:run_seq 独立取(INSERT 不支持 subquery/advisory-lock),冲突重试时重新取。
        paramMap.put("runSeq", platformFileRuntimeMapper.selectNextStepRunSeq(paramMap));
        platformFileRuntimeMapper.insertStepRun(paramMap);
        return toLong(paramMap.get(KEY_ID));
      } catch (DuplicateKeyException e) {
        paramMap.remove(KEY_ID);
        if (i == 4) {
          throw e;
        }
      }
    }
    return null;
  }

  public void finishStepRunSuccess(Long stepRunId, Object outputSummary) {
    finishStepRun(stepRunId, "SUCCESS", null, null, outputSummary);
  }

  public void finishStepRunFailure(
      Long stepRunId, String errorCode, String errorMessage, Object outputSummary) {
    finishStepRunFailure(stepRunId, errorCode, errorMessage, null, null, outputSummary);
  }

  public void finishStepRunFailure(
      Long stepRunId,
      String errorCode,
      String errorMessage,
      String errorKey,
      String errorArgs,
      Object outputSummary) {
    FinishStepRunParam param =
        FinishStepRunParam.builder()
            .stepRunId(stepRunId)
            .status("FAILED")
            .errorCode(errorCode)
            .errorMessage(errorMessage)
            .errorKey(errorKey)
            .errorArgs(errorArgs)
            .outputSummary(outputSummary)
            .build();
    finishStepRun(param);
  }

  private void finishStepRun(
      Long stepRunId, String status, String errorCode, String errorMessage, Object outputSummary) {
    FinishStepRunParam param =
        FinishStepRunParam.builder()
            .stepRunId(stepRunId)
            .status(status)
            .errorCode(errorCode)
            .errorMessage(errorMessage)
            .outputSummary(outputSummary)
            .build();
    finishStepRun(param);
  }

  private void finishStepRun(FinishStepRunParam param) {
    if (param.stepRunId() == null) {
      return;
    }
    platformFileRuntimeMapper.finishStepRun(
        params(
            "stepRunId", param.stepRunId(),
            "status", param.status(),
            "outputSummaryJson", toJson(param.outputSummary()),
            "errorCode", param.errorCode(),
            "errorMessage", truncate(param.errorMessage(), 1024),
            "errorKey", param.errorKey(),
            "errorArgs", param.errorArgs()));
  }

  @Builder
  private record FinishStepRunParam(
      Long stepRunId,
      String status,
      String errorCode,
      String errorMessage,
      String errorKey,
      String errorArgs,
      Object outputSummary) {}

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
    if (!Texts.hasText(tenantId)
        || !Texts.hasText(fileCategory)
        || !Texts.hasText(fileName)
        || !Texts.hasText(fileFormatType)
        || !Texts.hasText(storageType)
        || !Texts.hasText(storagePath)
        || !Texts.hasText(sourceType)
        || !Texts.hasText(fileStatus)) {
      return null;
    }
    FileStateMachine.assertInitialStatus(fileStatus);
    int nextGenerationNo = 1;
    if (Texts.hasText(fileCode)) {
      Integer maxGeneration =
          platformFileRuntimeMapper.selectMaxFileGenerationNo(
              params(KEY_TENANT_ID, tenantId, "fileCode", fileCode));
      nextGenerationNo = (maxGeneration == null ? 0 : maxGeneration) + 1;
      platformFileRuntimeMapper.markHistoricalFileNotLatest(
          params(KEY_TENANT_ID, tenantId, "fileCode", fileCode));
    }
    final int finalNextGenerationNo = nextGenerationNo;
    String resolvedFileVersion =
        Texts.hasText(fileVersion) ? fileVersion : "v" + finalNextGenerationNo;
    Map<String, Object> paramMap =
        params(
            KEY_TENANT_ID,
            tenantId,
            "fileCode",
            fileCode,
            "bizType",
            bizType,
            "fileCategory",
            fileCategory,
            "fileName",
            fileName,
            "originalFileName",
            originalFileName,
            "fileExt",
            resolveFileExt(fileName),
            "fileFormatType",
            fileFormatType,
            "charset",
            charset,
            "mimeType",
            resolveMimeType(fileFormatType),
            "fileSizeBytes",
            Math.max(fileSizeBytes, 0L),
            "checksumType",
            defaultText(checksumType, "NONE"),
            "checksumValue",
            checksumValue,
            "storageType",
            storageType,
            "storagePath",
            storagePath,
            "storageBucket",
            storageBucket,
            "fileVersion",
            resolvedFileVersion,
            "fileGenerationNo",
            finalNextGenerationNo,
            "sourceType",
            sourceType,
            "sourceRef",
            sourceRef,
            "fileStatus",
            fileStatus,
            "bizDate",
            bizDate,
            "traceId",
            traceId,
            "metadataJson",
            toJson(metadata));
    // R7 log-audit-bug R2 (v2 fix)：V124 加了 partial unique
    //   uk_file_record_no_checksum (tenant_id, storage_path) WHERE checksum_value IS NULL
    // IMPORT RECEIVE 阶段没算 checksum 就直接 INSERT；任务重试 / lease 过期 reCLAIM / DLQ 重投时
    // 同 (tenant_id, storage_path) 会撞约束，整个 RECEIVE 阶段抛 DuplicateKeyException 失败。
    //
    // **第一版**用 try/catch + 反查 — 但忽略了 PG 在 @Transactional 内 INSERT 抛错后整个事务被
    // 标记 aborted，后续 SELECT 直接抛 "current transaction is aborted" (SQLState 25P02)，
    // 反查路径被吞掉，import 仍然卡死。日志显示 worker-import 持续 10+/分钟同样异常。
    //
    // **正确做法**：INSERT 之前 pre-check (tenant_id, storage_path) WHERE checksum_value IS NULL，
    // 命中即直接复用 fileId；不命中再 INSERT。撞约束（极小概率并发场景）仍 catch 但只记 warn，
    // 不再尝试 select（事务已 aborted）。
    if (!Texts.hasText(checksumValue)) {
      Map<String, Object> existing =
          platformFileRuntimeMapper.selectFileRecordByStoragePath(
              params(
                  KEY_TENANT_ID,
                  tenantId,
                  "storageBucket",
                  storageBucket,
                  "storagePath",
                  storagePath));
      if (existing != null && existing.get(KEY_ID) != null) {
        Object existingChecksumObj = existing.get("checksum_value");
        String existingChecksum =
            existingChecksumObj == null ? null : existingChecksumObj.toString();
        if (existingChecksum == null || existingChecksum.isBlank()) {
          log.info(
              "file_record dedup pre-check hit (tenant={} storage_path={}), reuse existing"
                  + " fileId={}",
              tenantId,
              storagePath,
              existing.get(KEY_ID));
          return toLong(existing.get(KEY_ID));
        }
      }
    }
    try {
      platformFileRuntimeMapper.insertFileRecord(paramMap);
      return toLong(paramMap.get(KEY_ID));
    } catch (DuplicateKeyException ex) {
      // 极小概率：pre-check 没命中但 INSERT 撞约束（两个并发 worker 同一 storage_path）。
      // 事务已 aborted 不能 select，记 warn 让任务回退到 outer retry / DLQ 处置。
      if (!Texts.hasText(checksumValue)) {
        log.warn(
            "file_record dedup race lost (tenant={} storage_path={}): another transaction"
                + " inserted concurrently — current task will retry via outer mechanism",
            tenantId,
            storagePath);
      }
      throw ex;
    }
  }

  public void updateFileStatus(Long fileId, String fileStatus, Object metadata) {
    if (fileId == null || !Texts.hasText(fileStatus)) {
      return;
    }
    String currentStatus = platformFileRuntimeMapper.selectFileStatus(params(KEY_FILE_ID, fileId));
    if (!Texts.hasText(currentStatus)) {
      return;
    }
    FileStateMachine.assertTransition(currentStatus, fileStatus);
    platformFileRuntimeMapper.updateFileRecordStatus(
        params(KEY_FILE_ID, fileId, "fileStatus", fileStatus, "metadataJson", toJson(metadata)));
  }

  public void updateFileMetadata(Long fileId, Object metadata) {
    if (fileId == null) {
      return;
    }
    platformFileRuntimeMapper.updateFileRecordMetadata(
        params(KEY_FILE_ID, fileId, "metadataJson", toJson(metadata)));
  }

  public Long insertFileErrorRecord(FileErrorRecordParam p) {
    Map<String, Object> paramMap =
        params(
            KEY_TENANT_ID,
            p.getTenantId(),
            KEY_FILE_ID,
            p.getFileId(),
            KEY_PIPELINE_INSTANCE_ID,
            p.getPipelineInstanceId(),
            "pipelineStepRunId",
            p.getPipelineStepRunId(),
            "recordNo",
            p.getRecordNo(),
            "errorCode",
            p.getErrorCode(),
            "errorMessage",
            truncate(p.getErrorMessage(), 1024),
            "errorStage",
            p.getErrorStage(),
            "isSkipped",
            p.isSkipped(),
            "skipAction",
            p.getSkipAction(),
            "rawRecordJson",
            toJson(p.getRawRecord()));
    platformFileRuntimeMapper.insertFileErrorRecord(paramMap);
    return toLong(paramMap.get(KEY_ID));
  }

  public List<Map<String, Object>> loadFileErrorRecords(
      String tenantId, Long fileId, String errorCode, String errorStage, int limit) {
    if (!Texts.hasText(tenantId) || limit <= 0) {
      return List.of();
    }
    return platformFileRuntimeMapper.selectFileErrorRecords(
        params(
            KEY_TENANT_ID,
            tenantId,
            KEY_FILE_ID,
            fileId,
            "errorCode",
            errorCode,
            "errorStage",
            errorStage,
            "limit",
            limit));
  }

  public void appendAudit(FileAuditParam p) {
    if (p.getFileId() == null
        || !Texts.hasText(p.getTenantId())
        || !Texts.hasText(p.getOperationType())
        || !Texts.hasText(p.getOperationResult())) {
      return;
    }
    platformFileRuntimeMapper.insertFileAuditLog(
        params(
            KEY_TENANT_ID,
            p.getTenantId(),
            KEY_FILE_ID,
            p.getFileId(),
            "operationType",
            p.getOperationType(),
            "operationResult",
            p.getOperationResult(),
            "operatorType",
            defaultText(p.getOperatorType(), "SYSTEM"),
            "operatorId",
            p.getOperatorId(),
            "traceId",
            p.getTraceId(),
            "evidenceRef",
            p.getEvidenceRef(),
            "detailSummaryJson",
            toJson(p.getDetailSummary())));
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
                params(KEY_PIPELINE_DEFINITION_ID, pipelineDefinitionId, "enabledOnly", false)));
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
              KEY_PIPELINE_DEFINITION_ID,
              pipelineDefinitionId,
              "stepCode",
              template.stepCode(),
              "stepName",
              defaultText(template.stepName(), template.stepCode()),
              "stageCode",
              template.stageCode(),
              "stepOrder",
              template.stepOrder() == null ? 0 : template.stepOrder(),
              "implCode",
              template.implCode(),
              "stepParamsJson",
              toJson(template.stepParams()),
              "timeoutSeconds",
              template.timeoutSeconds() == null ? 0 : template.timeoutSeconds(),
              "retryPolicy",
              defaultText(template.retryPolicy(), "NONE"),
              "retryMaxCount",
              template.retryMaxCount() == null ? 0 : template.retryMaxCount(),
              "enabled",
              template.enabled()));
    }
  }

  private List<PipelineStepDefinition> mapPipelineStepDefinitions(List<Map<String, Object>> rows) {
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    List<PipelineStepDefinition> definitions = new ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) {
      PipelineStepDefinition definition =
          PipelineStepDefinition.builder()
              .id(toLong(row.get(KEY_ID)))
              .pipelineDefinitionId(toLong(row.get("pipeline_definition_id")))
              .stepCode(stringValue(row.get("step_code")))
              .stepName(stringValue(row.get("step_name")))
              .stageCode(stringValue(row.get("stage_code")))
              .stepOrder(toInteger(row.get("step_order")))
              .implCode(stringValue(row.get("impl_code")))
              .stepParams(toMap(row.get("step_params")))
              .timeoutSeconds(toInteger(row.get("timeout_seconds")))
              .retryPolicy(stringValue(row.get("retry_policy")))
              .retryMaxCount(toInteger(row.get("retry_max_count")))
              .enabled(Boolean.TRUE.equals(row.get("enabled")))
              .build();
      definitions.add(definition);
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
    if (!Texts.hasText(fileName) || !fileName.contains(".")) {
      return null;
    }
    return fileName.substring(fileName.lastIndexOf('.') + 1);
  }

  private String resolveMimeType(String fileFormatType) {
    if (!Texts.hasText(fileFormatType)) {
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
        || !Texts.hasText(template.stepCode())
        || existingCodes.contains(template.stepCode());
  }

  private String defaultText(String value, String fallback) {
    return Texts.hasText(value) ? value : fallback;
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
