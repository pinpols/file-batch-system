package io.github.pinpols.batch.orchestrator.infrastructure.file;

import io.github.pinpols.batch.common.constants.BatchFileConstants;
import io.github.pinpols.batch.common.enums.DictEnum;
import io.github.pinpols.batch.common.enums.FileDispatchRunStatus;
import io.github.pinpols.batch.common.enums.FileDispatchStatus;
import io.github.pinpols.batch.common.enums.FileReceiptStatus;
import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.common.enums.FileTemplateFormat;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.domain.param.FileGovernanceAuditInsertParam;
import io.github.pinpols.batch.orchestrator.domain.param.FileGovernanceReconcileInsertParam;
import io.github.pinpols.batch.orchestrator.domain.param.FileGovernanceStalePipelineParam;
import io.github.pinpols.batch.orchestrator.domain.param.FileGovernanceStaleStepParam;
import io.github.pinpols.batch.orchestrator.domain.param.FileGovernanceStatusUpdateParam;
import io.github.pinpols.batch.orchestrator.domain.query.FileGovernanceActivePipelineQuery;
import io.github.pinpols.batch.orchestrator.domain.query.FileGovernanceOrphanUploadQuery;
import io.github.pinpols.batch.orchestrator.domain.query.FileGovernancePendingDispatchQuery;
import io.github.pinpols.batch.orchestrator.domain.query.FileGovernanceProcessingDelayQuery;
import io.github.pinpols.batch.orchestrator.domain.view.FileGovernanceViews;
import io.github.pinpols.batch.orchestrator.mapper.FileGovernanceMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文件治理数据访问层（纯 DAO）：提供文件记录查询、状态更新、归档/删除、对账写入和审计追加等操作。
 *
 * <p>注：状态机校验（{@code FileStateMachine.assertTransition}）与 {@code BizException} 抛出均由 {@code
 * DefaultFileGovernanceService} 业务层负责，本类只做参数封装 + Mapper 调用。 内嵌 record 值对象（{@code
 * FileIdentity}、{@code FileStorage}、{@code ReconciledFileRecordCommand} 等） 封装对账写入和审计所需的参数，避免散落的
 * null 参数。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FileGovernanceRepository {

  private static final String PIPELINE_STATUS_RUNNING = "RUNNING";
  private static final String PIPELINE_STATUS_FAILED = "FAILED";

  public record FileIdentity(
      String tenantId, String fileCategory, String fileName, String fileFormatType) {}

  public record FileStorage(String storageType, String storagePath, String storageBucket) {}

  public record ReconciledFileRecordCommand(
      FileIdentity identity,
      long fileSizeBytes,
      FileStorage storage,
      String sourceType,
      String fileStatus,
      String traceId,
      Object metadata) {}

  public record FileAuditActor(String operatorType, String operatorId) {}

  public record FileAuditCommand(
      String tenantId,
      Long fileId,
      String operationType,
      String operationResult,
      FileAuditActor actor,
      String traceId,
      Object detailSummary) {}

  /** 审计明细固定列：状态跃迁 + 操作人 + 原因；FAILED 分支的 errorMessage 等动态证据仍以具名 Map 承载。 */
  public record OperationDetailView(
      String currentStatus, String nextStatus, String operatorId, String reason) {}

  private final FileGovernanceMapper fileGovernanceMapper;

  public FileGovernanceViews.FileRecordRow loadFileRecord(String tenantId, Long fileId) {
    if (!Texts.hasText(tenantId) || fileId == null) {
      return null;
    }
    return fileGovernanceMapper.selectFileRecord(tenantId, fileId);
  }

  public FileGovernanceViews.TemplateSecurityRow loadTemplateSecurityForFile(
      String tenantId, Long fileId) {
    if (!Texts.hasText(tenantId) || fileId == null) {
      return null;
    }
    return fileGovernanceMapper.selectFileTemplateSecurity(tenantId, fileId);
  }

  public long countActivePipelineInstances(String tenantId, Long fileId) {
    if (!Texts.hasText(tenantId) || fileId == null) {
      return 0L;
    }
    FileGovernanceActivePipelineQuery statementParams = FileGovernanceActivePipelineQuery.builder()
        .tenantId(tenantId)
        .fileId(fileId)
        .createdStatus(FileDispatchRunStatus.CREATED.code())
        .runningStatus(FileDispatchRunStatus.RUNNING.code())
        .compensatingStatus(FileDispatchRunStatus.COMPENSATING.code())
        .build();
    Long count = fileGovernanceMapper.countActivePipelineInstances(statementParams);
    return count == null ? 0L : count;
  }

  public long countPendingDispatchRecords(String tenantId, Long fileId) {
    if (!Texts.hasText(tenantId) || fileId == null) {
      return 0L;
    }
    FileGovernancePendingDispatchQuery statementParams =
        FileGovernancePendingDispatchQuery.builder()
            .tenantId(tenantId)
            .fileId(fileId)
            .dispatchCreatedStatus(FileDispatchStatus.CREATED.name())
            .dispatchSentStatus(FileDispatchStatus.SENT.name())
            .receiptPendingStatus(FileReceiptStatus.PENDING.name())
            .build();
    Long count = fileGovernanceMapper.countPendingDispatchRecords(statementParams);
    return count == null ? 0L : count;
  }

  public FileGovernanceViews.DispatchRecordRow loadLatestDispatchRecord(
      String tenantId, Long fileId, String channelCode) {
    if (!Texts.hasText(tenantId) || fileId == null) {
      return null;
    }
    return fileGovernanceMapper.selectLatestDispatchRecord(tenantId, fileId, channelCode);
  }

  public Long loadRelatedJobInstanceId(Long pipelineInstanceId) {
    if (pipelineInstanceId == null) {
      return null;
    }
    return fileGovernanceMapper.selectRelatedJobInstanceId(pipelineInstanceId);
  }

  public int resetDispatchRecordForRedispatch(String tenantId, Long dispatchRecordId) {
    if (!Texts.hasText(tenantId) || dispatchRecordId == null) {
      return 0;
    }
    return fileGovernanceMapper.resetDispatchRecordForRedispatch(
        tenantId,
        dispatchRecordId,
        FileDispatchStatus.FAILED.code(),
        FileReceiptStatus.NONE.code());
  }

  List<FileGovernanceMaintenanceViews.ArchivedFileCleanupView> selectArchivedFilesForCleanup(
      Instant cutoff, int limit) {
    return fileGovernanceMapper
        .selectArchivedFilesForCleanup(cutoff, limit, FileDispatchRunStatus.ARCHIVED.code())
        .stream()
        .map(FileGovernanceMaintenanceViews::archivedFile)
        .toList();
  }

  /** 托管上传会话孤儿候选:超过 TTL 仍停留在 RECEIVED + APP_MANAGED + WAITING_ARRIVAL 且从未确认到达的占位行。 */
  List<FileGovernanceMaintenanceViews.OrphanUploadSessionView> selectOrphanUploadSessions(
      long ttlSeconds, int limit) {
    if (ttlSeconds <= 0 || limit <= 0) {
      return List.of();
    }
    FileGovernanceOrphanUploadQuery statementParams = FileGovernanceOrphanUploadQuery.builder()
        .receivedStatus(FileStatus.RECEIVED.code())
        .uploadMode("APP_MANAGED")
        .waitingArrivalState("WAITING_ARRIVAL")
        .ttlSeconds(ttlSeconds)
        .limit(limit)
        .build();
    return fileGovernanceMapper.selectOrphanUploadSessions(statementParams).stream()
        .map(FileGovernanceMaintenanceViews::orphanUploadSession)
        .toList();
  }

  public List<FileGovernanceArrivalViews.ArrivalCandidateView> selectArrivalGovernanceCandidates(
      int limit) {
    if (limit <= 0) {
      return List.of();
    }
    return fileGovernanceMapper.selectArrivalGovernanceCandidates(limit).stream()
        .map(FileGovernanceArrivalViews::arrivalCandidate)
        .toList();
  }

  FileGovernanceArrivalViews.ArrivalGroupBacklogView selectArrivalGroupBacklogMetrics() {
    FileGovernanceViews.ArrivalGroupBacklogRow row =
        fileGovernanceMapper.selectArrivalGroupBacklogMetrics();
    return EmptyChecks.isNull(row)
        ? new FileGovernanceArrivalViews.ArrivalGroupBacklogView(0L, 0L)
        : FileGovernanceArrivalViews.arrivalGroupBacklog(row);
  }

  public List<FileGovernanceArrivalViews.ArrivalGroupSummaryView> selectArrivalGroupSummaries(
      String tenantId, String fileGroupCode, String arrivalState) {
    return fileGovernanceMapper
        .selectArrivalGroupSummaries(tenantId, fileGroupCode, arrivalState)
        .stream()
        .map(FileGovernanceArrivalViews::arrivalGroupSummary)
        .toList();
  }

  public List<FileGovernanceArrivalViews.ArrivalGroupFileView> selectArrivalGroupFiles(
      String tenantId, String fileGroupCode) {
    return selectArrivalGroupFiles(tenantId, fileGroupCode, null);
  }

  public List<FileGovernanceArrivalViews.ArrivalGroupFileView> selectArrivalGroupFiles(
      String tenantId, String fileGroupCode, String bizDate) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(fileGroupCode)) {
      return List.of();
    }
    return fileGovernanceMapper.selectArrivalGroupFiles(tenantId, fileGroupCode, bizDate).stream()
        .map(FileGovernanceArrivalViews::arrivalGroupFile)
        .toList();
  }

  public long countArrivalDelayViolations(String tenantId, long thresholdSeconds) {
    if (!Texts.hasText(tenantId)) {
      return 0L;
    }
    Long count = fileGovernanceMapper.countArrivalDelayViolations(tenantId, thresholdSeconds);
    return count == null ? 0L : count;
  }

  public long maxArrivalDelaySeconds(String tenantId) {
    if (!Texts.hasText(tenantId)) {
      return 0L;
    }
    Long maxDelay = fileGovernanceMapper.selectMaxArrivalDelaySeconds(tenantId);
    return maxDelay == null ? 0L : maxDelay;
  }

  public List<FileGovernanceViews.ArrivalDelaySampleRow> selectArrivalDelaySamples(
      String tenantId, long thresholdSeconds, int limit) {
    if (!Texts.hasText(tenantId)) {
      return List.of();
    }
    return fileGovernanceMapper.selectArrivalDelaySamples(tenantId, thresholdSeconds, limit);
  }

  public long countProcessingDelayViolations(
      String tenantId, long thresholdSeconds, long maxAgeSeconds) {
    if (!Texts.hasText(tenantId)) {
      return 0L;
    }
    Long count = fileGovernanceMapper.countProcessingDelayViolations(
        tenantId, thresholdSeconds, maxAgeSeconds, FileDispatchRunStatus.RUNNING.code());
    return count == null ? 0L : count;
  }

  public long maxProcessingDelaySeconds(String tenantId, long maxAgeSeconds) {
    if (!Texts.hasText(tenantId)) {
      return 0L;
    }
    Long maxDelay = fileGovernanceMapper.selectMaxProcessingDelaySeconds(
        tenantId, maxAgeSeconds, FileDispatchRunStatus.RUNNING.code());
    return maxDelay == null ? 0L : maxDelay;
  }

  public List<FileGovernanceViews.ProcessingDelaySampleRow> selectProcessingDelaySamples(
      String tenantId, long thresholdSeconds, long maxAgeSeconds, int limit) {
    if (!Texts.hasText(tenantId)) {
      return List.of();
    }
    FileGovernanceProcessingDelayQuery queryParams = FileGovernanceProcessingDelayQuery.builder()
        .tenantId(tenantId)
        .thresholdSeconds(thresholdSeconds)
        .maxAgeSeconds(maxAgeSeconds)
        .limit(limit)
        .runningStatus(FileDispatchRunStatus.RUNNING.code())
        .build();
    return fileGovernanceMapper.selectProcessingDelaySamples(queryParams);
  }

  public boolean existsFileRecordByStoragePath(
      String tenantId, String storageBucket, String storagePath) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(storagePath)) {
      return false;
    }
    Long count =
        fileGovernanceMapper.countFileRecordByStoragePath(tenantId, storageBucket, storagePath);
    return count != null && count > 0;
  }

  public Long createReconciledFileRecord(ReconciledFileRecordCommand command) {
    return createReconciledFileRecord(command, null);
  }

  public Long createReconciledFileRecord(ReconciledFileRecordCommand command, LocalDate bizDate) {
    FileGovernanceReconcileInsertParam params = FileGovernanceReconcileInsertParam.builder()
        .tenantId(command.identity().tenantId())
        .fileCategory(command.identity().fileCategory())
        .fileName(command.identity().fileName())
        .fileExt(resolveFileExt(command.identity().fileName()))
        .fileFormatType(command.identity().fileFormatType())
        .mimeType(resolveMimeType(command.identity().fileFormatType()))
        .fileSizeBytes(Math.max(command.fileSizeBytes(), 0L))
        .storageType(command.storage().storageType())
        .storagePath(command.storage().storagePath())
        .storageBucket(command.storage().storageBucket())
        .sourceType(command.sourceType())
        .fileStatus(command.fileStatus())
        .bizDate(bizDate)
        .traceId(command.traceId())
        .metadataJson(toJson(command.metadata()))
        .build();
    try {
      fileGovernanceMapper.insertReconciledFileRecord(params);
    } catch (DuplicateKeyException exception) {
      log.info(
          "reconciled file record already exists, skip duplicate insert: tenantId={},"
              + " storagePath={}",
          command.identity().tenantId(),
          command.storage().storagePath());
      return null;
    }
    return params.getId();
  }

  public List<Long> markStaleRunningPipelineInstancesFailed(
      String tenantId, long staleSeconds, int limit) {
    if (!Texts.hasText(tenantId) || staleSeconds <= 0 || limit <= 0) {
      return List.of();
    }
    FileGovernanceStalePipelineParam statementParams = FileGovernanceStalePipelineParam.builder()
        .tenantId(tenantId)
        .runningStatus(PIPELINE_STATUS_RUNNING)
        .failedStatus(PIPELINE_STATUS_FAILED)
        .staleSeconds(staleSeconds)
        .limit(limit)
        .build();
    return fileGovernanceMapper.markStaleRunningPipelineInstancesFailed(statementParams);
  }

  /** stale running 清理两步结果:置 FAILED 的 pipeline 实例数 + 连带置 FAILED 的 step 数。 */
  public record StaleSweepResult(int failedPipelines, int failedSteps) {}

  /**
   * 把超时 RUNNING 的 pipeline 实例与其 step **同事务**置 FAILED。原先两步分属独立事务,第一步提交后崩溃会让 step 永久滞留 RUNNING(下轮扫描按
   * pipeline_instance.updated_at 过滤,已 FAILED 的实例不再命中)。合并到一个事务,要么全成要么全回滚。
   */
  @Transactional
  public StaleSweepResult markStaleRunningPipelinesAndStepsFailed(
      String tenantId, long staleSeconds, int limit) {
    List<Long> failedPipelineIds =
        markStaleRunningPipelineInstancesFailed(tenantId, staleSeconds, limit);
    if (EmptyChecks.isEmpty(failedPipelineIds)) {
      return new StaleSweepResult(0, 0);
    }
    int failedSteps = markRunningPipelineStepsFailedForInstances(tenantId, failedPipelineIds);
    return new StaleSweepResult(failedPipelineIds.size(), failedSteps);
  }

  public int markRunningPipelineStepsFailedForInstances(
      String tenantId, List<Long> pipelineInstanceIds) {
    if (!Texts.hasText(tenantId) || EmptyChecks.isEmpty(pipelineInstanceIds)) {
      return 0;
    }
    FileGovernanceStaleStepParam statementParams = FileGovernanceStaleStepParam.builder()
        .tenantId(tenantId)
        .failedStatus(PIPELINE_STATUS_FAILED)
        .pendingStatus("PENDING")
        .runningStatus(PIPELINE_STATUS_RUNNING)
        .retryingStatus("RETRYING")
        .failedPipelineStatus(PIPELINE_STATUS_FAILED)
        .errorCode("PIPELINE_STALE_RUNNING")
        .errorMessage("pipeline was marked FAILED by stale running sweep")
        .pipelineInstanceIds(pipelineInstanceIds)
        .build();
    return fileGovernanceMapper.markRunningPipelineStepsFailedForInstances(statementParams);
  }

  /**
   * 纯 DAO 写入：调用前调用方需自行执行 {@code FileStateMachine.assertTransition} 校验合法跃迁， 并对返回值 ≤ 0 抛出 {@code
   * BizException(STATE_CONFLICT)} 处理并发冲突。
   *
   * @return 实际更新的行数（0 表示并发冲突 / 行不存在）
   */
  public int updateFileStatus(
      String tenantId, Long fileId, String currentStatus, String nextStatus, Object metadata) {
    String metadataJson = toJson(metadata);
    FileGovernanceStatusUpdateParam update = FileGovernanceStatusUpdateParam.builder()
        .tenantId(tenantId)
        .fileId(fileId)
        .currentStatus(currentStatus)
        .nextStatus(nextStatus)
        .metadataJson(metadataJson)
        .build();
    return fileGovernanceMapper.updateFileStatus(update);
  }

  public void updateFileMetadata(String tenantId, Long fileId, Object metadata) {
    if (!Texts.hasText(tenantId) || fileId == null) {
      return;
    }
    String metadataJson = toJson(metadata);

    fileGovernanceMapper.updateFileMetadata(tenantId, fileId, metadataJson);
  }

  public int updateArrivalGroupMetadataIfNonTerminal(
      String tenantId, Long fileId, Object metadata) {
    if (!Texts.hasText(tenantId) || EmptyChecks.isNull(fileId)) {
      return 0;
    }
    String metadataJson = toJson(metadata);

    return fileGovernanceMapper.updateArrivalGroupMetadataIfNonTerminal(
        tenantId, fileId, metadataJson);
  }

  public void markFileArrivalConfirmed(
      String tenantId, Long fileId, long fileSizeBytes, Object metadata) {
    if (!Texts.hasText(tenantId) || fileId == null) {
      return;
    }
    long normalizedFileSize = Math.max(fileSizeBytes, 0L);
    String metadataJson = toJson(metadata);

    fileGovernanceMapper.markFileArrivalConfirmed(
        tenantId, fileId, normalizedFileSize, metadataJson);
  }

  public void appendAudit(FileAuditCommand command) {
    boolean fileAuditCommandIsIncomplete = EmptyChecks.isNull(command)
        || !Texts.hasText(command.tenantId())
        || command.fileId() == null
        || !Texts.hasText(command.operationType())
        || !Texts.hasText(command.operationResult());
    if (fileAuditCommandIsIncomplete) {
      return;
    }
    String auditTenantId = command.tenantId();
    Long auditFileId = command.fileId();
    String auditOperationType = command.operationType();
    String auditOperationResult = command.operationResult();
    String auditOperatorType = defaultText(
        EmptyChecks.isNull(command.actor()) ? null : command.actor().operatorType(), "API");
    String auditOperatorId =
        EmptyChecks.isNull(command.actor()) ? null : command.actor().operatorId();
    String auditTraceId = command.traceId();
    String detailSummaryJson = toJson(command.detailSummary());
    FileGovernanceAuditInsertParam auditParams = FileGovernanceAuditInsertParam.builder()
        .tenantId(auditTenantId)
        .fileId(auditFileId)
        .operationType(auditOperationType)
        .operationResult(auditOperationResult)
        .operatorType(auditOperatorType)
        .operatorId(auditOperatorId)
        .traceId(auditTraceId)
        .detailSummaryJson(detailSummaryJson)
        .build();
    fileGovernanceMapper.insertFileAuditLog(auditParams);
  }

  public OperationDetailView operationDetail(
      String currentStatus, String nextStatus, String operatorId, String reason) {
    return new OperationDetailView(currentStatus, nextStatus, operatorId, reason);
  }

  private String toJson(Object value) {
    return value == null ? null : JsonUtils.toJson(value);
  }

  private String defaultText(String value, String fallback) {
    return Texts.hasText(value) ? value : fallback;
  }

  private String resolveFileExt(String fileName) {
    if (!Texts.hasText(fileName) || !fileName.contains(".")) {
      return null;
    }
    return fileName.substring(fileName.lastIndexOf('.') + 1);
  }

  private String resolveMimeType(String fileFormatType) {
    if (!Texts.hasText(fileFormatType)) {
      return BatchFileConstants.CONTENT_TYPE_OCTET_STREAM;
    }
    FileTemplateFormat format = DictEnum.fromCode(FileTemplateFormat.class, fileFormatType);
    if (EmptyChecks.isNull(format)) {
      return BatchFileConstants.CONTENT_TYPE_OCTET_STREAM;
    }
    return switch (format) {
      case JSON -> BatchFileConstants.CONTENT_TYPE_JSON;
      case XML -> BatchFileConstants.CONTENT_TYPE_XML;
      case EXCEL -> BatchFileConstants.CONTENT_TYPE_EXCEL;
      case DELIMITED -> BatchFileConstants.CONTENT_TYPE_CSV;
      default -> BatchFileConstants.CONTENT_TYPE_OCTET_STREAM;
    };
  }
}
