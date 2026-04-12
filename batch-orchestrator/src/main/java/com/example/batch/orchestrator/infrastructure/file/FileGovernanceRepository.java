package com.example.batch.orchestrator.infrastructure.file;

import com.example.batch.common.enums.FileDispatchRunStatus;
import com.example.batch.common.enums.FileDispatchStatus;
import com.example.batch.common.enums.FileReceiptStatus;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.FileStateMachine;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.mapper.FileGovernanceMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class FileGovernanceRepository {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String KEY_RUNNING_STATUS = "runningStatus";
  private static final String KEY_LIMIT = "limit";
  private static final String KEY_THRESHOLD_SECONDS = "thresholdSeconds";
  private static final String KEY_TENANT_ID = "tenantId";
  private static final String KEY_FILE_ID = "fileId";

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

  private final FileGovernanceMapper fileGovernanceMapper;

  public Map<String, Object> loadFileRecord(String tenantId, Long fileId) {
    if (!StringUtils.hasText(tenantId) || fileId == null) {
      return Map.of();
    }
    Map<String, Object> fileRecord =
        fileGovernanceMapper.selectFileRecord(params(KEY_TENANT_ID, tenantId, KEY_FILE_ID, fileId));
    return fileRecord == null ? Map.of() : fileRecord;
  }

  public Map<String, Object> loadTemplateSecurityForFile(String tenantId, Long fileId) {
    if (!StringUtils.hasText(tenantId) || fileId == null) {
      return Map.of();
    }
    Map<String, Object> security =
        fileGovernanceMapper.selectFileTemplateSecurity(
            params(KEY_TENANT_ID, tenantId, KEY_FILE_ID, fileId));
    return security == null ? Map.of() : security;
  }

  public long countActivePipelineInstances(String tenantId, Long fileId) {
    if (!StringUtils.hasText(tenantId) || fileId == null) {
      return 0L;
    }
    Long count =
        fileGovernanceMapper.countActivePipelineInstances(
            params(
                KEY_TENANT_ID, tenantId,
                KEY_FILE_ID, fileId,
                "createdStatus", FileDispatchRunStatus.CREATED.code(),
                KEY_RUNNING_STATUS, FileDispatchRunStatus.RUNNING.code(),
                "compensatingStatus", FileDispatchRunStatus.COMPENSATING.code()));
    return count == null ? 0L : count;
  }

  public long countPendingDispatchRecords(String tenantId, Long fileId) {
    if (!StringUtils.hasText(tenantId) || fileId == null) {
      return 0L;
    }
    Long count =
        fileGovernanceMapper.countPendingDispatchRecords(
            params(
                KEY_TENANT_ID, tenantId,
                KEY_FILE_ID, fileId,
                "dispatchCreatedStatus", FileDispatchStatus.CREATED.name(),
                "dispatchSentStatus", FileDispatchStatus.SENT.name(),
                "receiptPendingStatus", FileReceiptStatus.PENDING.name()));
    return count == null ? 0L : count;
  }

  public Map<String, Object> loadLatestDispatchRecord(
      String tenantId, Long fileId, String channelCode) {
    if (!StringUtils.hasText(tenantId) || fileId == null) {
      return Map.of();
    }
    Map<String, Object> dispatchRecord =
        fileGovernanceMapper.selectLatestDispatchRecord(
            params(KEY_TENANT_ID, tenantId, KEY_FILE_ID, fileId, "channelCode", channelCode));
    return dispatchRecord == null ? Map.of() : dispatchRecord;
  }

  public Long loadRelatedJobInstanceId(Long pipelineInstanceId) {
    if (pipelineInstanceId == null) {
      return null;
    }
    return fileGovernanceMapper.selectRelatedJobInstanceId(
        params("pipelineInstanceId", pipelineInstanceId));
  }

  public void resetDispatchRecordForRedispatch(String tenantId, Long dispatchRecordId) {
    if (!StringUtils.hasText(tenantId) || dispatchRecordId == null) {
      return;
    }
    fileGovernanceMapper.resetDispatchRecordForRedispatch(
        params(
            KEY_TENANT_ID, tenantId,
            "dispatchRecordId", dispatchRecordId,
            "dispatchCreatedStatus", FileDispatchStatus.CREATED.name()));
  }

  public List<Map<String, Object>> selectArchivedFilesForCleanup(Instant cutoff, int limit) {
    return fileGovernanceMapper.selectArchivedFilesForCleanup(
        params(
            "cutoff", cutoff,
            KEY_LIMIT, limit,
            "archivedStatus", FileDispatchRunStatus.ARCHIVED.code()));
  }

  public List<Map<String, Object>> selectArrivalGovernanceCandidates(int limit) {
    if (limit <= 0) {
      return List.of();
    }
    return fileGovernanceMapper.selectArrivalGovernanceCandidates(params(KEY_LIMIT, limit));
  }

  public List<Map<String, Object>> selectArrivalGroupSummaries(
      String tenantId, String fileGroupCode, String arrivalState) {
    return fileGovernanceMapper.selectArrivalGroupSummaries(
        params(
            KEY_TENANT_ID, tenantId,
            "fileGroupCode", fileGroupCode,
            "arrivalState", arrivalState));
  }

  public List<Map<String, Object>> selectArrivalGroupFiles(String tenantId, String fileGroupCode) {
    if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(fileGroupCode)) {
      return List.of();
    }
    return fileGovernanceMapper.selectArrivalGroupFiles(
        params(KEY_TENANT_ID, tenantId, "fileGroupCode", fileGroupCode));
  }

  public long countArrivalDelayViolations(String tenantId, long thresholdSeconds) {
    if (!StringUtils.hasText(tenantId)) {
      return 0L;
    }
    Long count =
        fileGovernanceMapper.countArrivalDelayViolations(
            params(
                KEY_TENANT_ID, tenantId,
                KEY_THRESHOLD_SECONDS, thresholdSeconds));
    return count == null ? 0L : count;
  }

  public long maxArrivalDelaySeconds(String tenantId) {
    if (!StringUtils.hasText(tenantId)) {
      return 0L;
    }
    Long maxDelay = fileGovernanceMapper.selectMaxArrivalDelaySeconds(params(KEY_TENANT_ID, tenantId));
    return maxDelay == null ? 0L : maxDelay;
  }

  public List<Map<String, Object>> selectArrivalDelaySamples(
      String tenantId, long thresholdSeconds, int limit) {
    if (!StringUtils.hasText(tenantId)) {
      return List.of();
    }
    return fileGovernanceMapper.selectArrivalDelaySamples(
        params(
            KEY_TENANT_ID, tenantId,
            KEY_THRESHOLD_SECONDS, thresholdSeconds,
            KEY_LIMIT, limit));
  }

  public long countProcessingDelayViolations(String tenantId, long thresholdSeconds) {
    if (!StringUtils.hasText(tenantId)) {
      return 0L;
    }
    Long count =
        fileGovernanceMapper.countProcessingDelayViolations(
            params(
                KEY_TENANT_ID, tenantId,
                KEY_THRESHOLD_SECONDS, thresholdSeconds,
                KEY_RUNNING_STATUS, FileDispatchRunStatus.RUNNING.code()));
    return count == null ? 0L : count;
  }

  public long maxProcessingDelaySeconds(String tenantId) {
    if (!StringUtils.hasText(tenantId)) {
      return 0L;
    }
    Long maxDelay =
        fileGovernanceMapper.selectMaxProcessingDelaySeconds(
            params(KEY_TENANT_ID, tenantId, KEY_RUNNING_STATUS, FileDispatchRunStatus.RUNNING.code()));
    return maxDelay == null ? 0L : maxDelay;
  }

  public List<Map<String, Object>> selectProcessingDelaySamples(
      String tenantId, long thresholdSeconds, int limit) {
    if (!StringUtils.hasText(tenantId)) {
      return List.of();
    }
    return fileGovernanceMapper.selectProcessingDelaySamples(
        params(
            KEY_TENANT_ID, tenantId,
            KEY_THRESHOLD_SECONDS, thresholdSeconds,
            KEY_LIMIT, limit,
            KEY_RUNNING_STATUS, FileDispatchRunStatus.RUNNING.code()));
  }

  public boolean existsFileRecordByStoragePath(
      String tenantId, String storageBucket, String storagePath) {
    if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(storagePath)) {
      return false;
    }
    Long count =
        fileGovernanceMapper.countFileRecordByStoragePath(
            params(
                KEY_TENANT_ID, tenantId, "storageBucket", storageBucket, "storagePath", storagePath));
    return count != null && count > 0;
  }

  public Long createReconciledFileRecord(ReconciledFileRecordCommand command) {
    Map<String, Object> params =
        params(
            KEY_TENANT_ID, command.identity().tenantId(),
            "fileCategory", command.identity().fileCategory(),
            "fileName", command.identity().fileName(),
            "fileExt", resolveFileExt(command.identity().fileName()),
            "fileFormatType", command.identity().fileFormatType(),
            "mimeType", resolveMimeType(command.identity().fileFormatType()),
            "fileSizeBytes", Math.max(command.fileSizeBytes(), 0L),
            "storageType", command.storage().storageType(),
            "storagePath", command.storage().storagePath(),
            "storageBucket", command.storage().storageBucket(),
            "sourceType", command.sourceType(),
            "fileStatus", command.fileStatus(),
            "traceId", command.traceId(),
            "metadataJson", toJson(command.metadata()));
    fileGovernanceMapper.insertReconciledFileRecord(params);
    return toLong(params.get("id"));
  }

  public void updateFileStatus(String tenantId, Long fileId, String nextStatus, Object metadata) {
    Map<String, Object> fileRecord = loadFileRecord(tenantId, fileId);
    if (fileRecord.isEmpty()) {
      return;
    }
    String currentStatus =
        fileRecord.get("file_status") == null
            ? null
            : String.valueOf(fileRecord.get("file_status"));
    FileStateMachine.assertTransition(currentStatus, nextStatus);
    int updated =
        fileGovernanceMapper.updateFileStatus(
            params(
                KEY_TENANT_ID,
                tenantId,
                KEY_FILE_ID,
                fileId,
                "currentStatus",
                currentStatus,
                "nextStatus",
                nextStatus,
                "metadataJson",
                toJson(metadata)));
    if (updated <= 0) {
      throw new BizException(
          ResultCode.STATE_CONFLICT, "file status changed concurrently, expected " + currentStatus);
    }
  }

  public void updateFileMetadata(String tenantId, Long fileId, Object metadata) {
    if (!StringUtils.hasText(tenantId) || fileId == null) {
      return;
    }
    fileGovernanceMapper.updateFileMetadata(
        params(KEY_TENANT_ID, tenantId, KEY_FILE_ID, fileId, "metadataJson", toJson(metadata)));
  }

  public void appendAudit(FileAuditCommand command) {
    if (command == null
        || !StringUtils.hasText(command.tenantId())
        || command.fileId() == null
        || !StringUtils.hasText(command.operationType())
        || !StringUtils.hasText(command.operationResult())) {
      return;
    }
    fileGovernanceMapper.insertFileAuditLog(
        params(
            KEY_TENANT_ID, command.tenantId(),
            KEY_FILE_ID, command.fileId(),
            "operationType", command.operationType(),
            "operationResult", command.operationResult(),
            "operatorType",
                defaultText(command.actor() == null ? null : command.actor().operatorType(), "API"),
            "operatorId", command.actor() == null ? null : command.actor().operatorId(),
            "traceId", command.traceId(),
            "detailSummaryJson", toJson(command.detailSummary())));
  }

  public Map<String, Object> operationDetail(
      String currentStatus, String nextStatus, String operatorId, String reason) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("currentStatus", currentStatus);
    detail.put("nextStatus", nextStatus);
    detail.put("operatorId", operatorId);
    detail.put("reason", reason);
    return detail;
  }

  private String toJson(Object value) {
    return value == null ? null : JsonUtils.toJson(value);
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

  private Long toLong(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    String text = String.valueOf(value);
    return text.isBlank() ? null : Long.valueOf(text);
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
      case "DELIMITED" -> "text/csv";
      default -> "application/octet-stream";
    };
  }
}
