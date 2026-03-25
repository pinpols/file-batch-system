package com.example.batch.orchestrator.infrastructure.file;

import com.example.batch.common.enums.FileDispatchRunStatus;
import com.example.batch.common.enums.FileDispatchStatus;
import com.example.batch.common.enums.FileReceiptStatus;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.FileStateMachine;
import com.example.batch.common.utils.JsonUtils;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import com.example.batch.orchestrator.mapper.FileGovernanceMapper;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class FileGovernanceRepository {

    private final FileGovernanceMapper fileGovernanceMapper;

    public Map<String, Object> loadFileRecord(String tenantId, Long fileId) {
        if (!StringUtils.hasText(tenantId) || fileId == null) {
            return Map.of();
        }
        Map<String, Object> fileRecord = fileGovernanceMapper.selectFileRecord(params("tenantId", tenantId, "fileId", fileId));
        return fileRecord == null ? Map.of() : fileRecord;
    }

    public Map<String, Object> loadTemplateSecurityForFile(String tenantId, Long fileId) {
        if (!StringUtils.hasText(tenantId) || fileId == null) {
            return Map.of();
        }
        Map<String, Object> security = fileGovernanceMapper.selectFileTemplateSecurity(params("tenantId", tenantId, "fileId", fileId));
        return security == null ? Map.of() : security;
    }

    public long countActivePipelineInstances(String tenantId, Long fileId) {
        if (!StringUtils.hasText(tenantId) || fileId == null) {
            return 0L;
        }
        Long count = fileGovernanceMapper.countActivePipelineInstances(params(
                "tenantId", tenantId,
                "fileId", fileId,
                "createdStatus", FileDispatchRunStatus.CREATED.code(),
                "runningStatus", FileDispatchRunStatus.RUNNING.code(),
                "compensatingStatus", FileDispatchRunStatus.COMPENSATING.code()
        ));
        return count == null ? 0L : count;
    }

    public long countPendingDispatchRecords(String tenantId, Long fileId) {
        if (!StringUtils.hasText(tenantId) || fileId == null) {
            return 0L;
        }
        Long count = fileGovernanceMapper.countPendingDispatchRecords(params(
                "tenantId", tenantId,
                "fileId", fileId,
                "dispatchCreatedStatus", FileDispatchStatus.CREATED.name(),
                "dispatchSentStatus", FileDispatchStatus.SENT.name(),
                "receiptPendingStatus", FileReceiptStatus.PENDING.name()
        ));
        return count == null ? 0L : count;
    }

    public Map<String, Object> loadLatestDispatchRecord(String tenantId, Long fileId, String channelCode) {
        if (!StringUtils.hasText(tenantId) || fileId == null) {
            return Map.of();
        }
        Map<String, Object> dispatchRecord = fileGovernanceMapper.selectLatestDispatchRecord(
                params("tenantId", tenantId, "fileId", fileId, "channelCode", channelCode)
        );
        return dispatchRecord == null ? Map.of() : dispatchRecord;
    }

    public Long loadRelatedJobInstanceId(Long pipelineInstanceId) {
        if (pipelineInstanceId == null) {
            return null;
        }
        return fileGovernanceMapper.selectRelatedJobInstanceId(params("pipelineInstanceId", pipelineInstanceId));
    }

    public void resetDispatchRecordForRedispatch(String tenantId, Long dispatchRecordId) {
        if (!StringUtils.hasText(tenantId) || dispatchRecordId == null) {
            return;
        }
        fileGovernanceMapper.resetDispatchRecordForRedispatch(params(
                "tenantId", tenantId,
                "dispatchRecordId", dispatchRecordId,
                "dispatchCreatedStatus", FileDispatchStatus.CREATED.name()
        ));
    }

    public List<Map<String, Object>> selectArchivedFilesForCleanup(Instant cutoff, int limit) {
        return fileGovernanceMapper.selectArchivedFilesForCleanup(params(
                "cutoff", cutoff,
                "limit", limit,
                "archivedStatus", FileDispatchRunStatus.ARCHIVED.code()
        ));
    }

    public List<Map<String, Object>> selectArrivalGovernanceCandidates(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return fileGovernanceMapper.selectArrivalGovernanceCandidates(params("limit", limit));
    }

    public List<Map<String, Object>> selectArrivalGroupSummaries(String tenantId, String fileGroupCode, String arrivalState) {
        return fileGovernanceMapper.selectArrivalGroupSummaries(params(
                "tenantId", tenantId,
                "fileGroupCode", fileGroupCode,
                "arrivalState", arrivalState
        ));
    }

    public List<Map<String, Object>> selectArrivalGroupFiles(String tenantId, String fileGroupCode) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(fileGroupCode)) {
            return List.of();
        }
        return fileGovernanceMapper.selectArrivalGroupFiles(params("tenantId", tenantId, "fileGroupCode", fileGroupCode));
    }

    public long countArrivalDelayViolations(long thresholdSeconds) {
        Long count = fileGovernanceMapper.countArrivalDelayViolations(params("thresholdSeconds", thresholdSeconds));
        return count == null ? 0L : count;
    }

    public long maxArrivalDelaySeconds() {
        Long maxDelay = fileGovernanceMapper.selectMaxArrivalDelaySeconds();
        return maxDelay == null ? 0L : maxDelay;
    }

    public List<Map<String, Object>> selectArrivalDelaySamples(long thresholdSeconds, int limit) {
        return fileGovernanceMapper.selectArrivalDelaySamples(params("thresholdSeconds", thresholdSeconds, "limit", limit));
    }

    public long countProcessingDelayViolations(long thresholdSeconds) {
        Long count = fileGovernanceMapper.countProcessingDelayViolations(params(
                "thresholdSeconds", thresholdSeconds,
                "runningStatus", FileDispatchRunStatus.RUNNING.code()
        ));
        return count == null ? 0L : count;
    }

    public long maxProcessingDelaySeconds() {
        Long maxDelay = fileGovernanceMapper.selectMaxProcessingDelaySeconds(params(
                "runningStatus", FileDispatchRunStatus.RUNNING.code()
        ));
        return maxDelay == null ? 0L : maxDelay;
    }

    public List<Map<String, Object>> selectProcessingDelaySamples(long thresholdSeconds, int limit) {
        return fileGovernanceMapper.selectProcessingDelaySamples(params(
                "thresholdSeconds", thresholdSeconds,
                "limit", limit,
                "runningStatus", FileDispatchRunStatus.RUNNING.code()
        ));
    }

    public boolean existsFileRecordByStoragePath(String tenantId, String storageBucket, String storagePath) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(storagePath)) {
            return false;
        }
        Long count = fileGovernanceMapper.countFileRecordByStoragePath(
                params("tenantId", tenantId, "storageBucket", storageBucket, "storagePath", storagePath)
        );
        return count != null && count > 0;
    }

    public Long createReconciledFileRecord(String tenantId,
                                           String fileCategory,
                                           String fileName,
                                           String fileFormatType,
                                           long fileSizeBytes,
                                           String storageType,
                                           String storagePath,
                                           String storageBucket,
                                           String sourceType,
                                           String fileStatus,
                                           String traceId,
                                           Object metadata) {
        Map<String, Object> params = params(
                "tenantId", tenantId,
                "fileCategory", fileCategory,
                "fileName", fileName,
                "fileExt", resolveFileExt(fileName),
                "fileFormatType", fileFormatType,
                "mimeType", resolveMimeType(fileFormatType),
                "fileSizeBytes", Math.max(fileSizeBytes, 0L),
                "storageType", storageType,
                "storagePath", storagePath,
                "storageBucket", storageBucket,
                "sourceType", sourceType,
                "fileStatus", fileStatus,
                "traceId", traceId,
                "metadataJson", toJson(metadata)
        );
        fileGovernanceMapper.insertReconciledFileRecord(params);
        return toLong(params.get("id"));
    }

    public void updateFileStatus(String tenantId, Long fileId, String nextStatus, Object metadata) {
        Map<String, Object> fileRecord = loadFileRecord(tenantId, fileId);
        if (fileRecord.isEmpty()) {
            return;
        }
        String currentStatus = fileRecord.get("file_status") == null ? null : String.valueOf(fileRecord.get("file_status"));
        FileStateMachine.assertTransition(currentStatus, nextStatus);
        int updated = fileGovernanceMapper.updateFileStatus(
                params("tenantId", tenantId, "fileId", fileId,
                        "currentStatus", currentStatus, "nextStatus", nextStatus, "metadataJson", toJson(metadata))
        );
        if (updated <= 0) {
            throw new BizException(ResultCode.STATE_CONFLICT,
                    "file status changed concurrently, expected " + currentStatus);
        }
    }

    public void updateFileMetadata(String tenantId, Long fileId, Object metadata) {
        if (!StringUtils.hasText(tenantId) || fileId == null) {
            return;
        }
        fileGovernanceMapper.updateFileMetadata(
                params("tenantId", tenantId, "fileId", fileId, "metadataJson", toJson(metadata))
        );
    }

    public void appendAudit(String tenantId,
                            Long fileId,
                            String operationType,
                            String operationResult,
                            String operatorType,
                            String operatorId,
                            String traceId,
                            Object detailSummary) {
        if (!StringUtils.hasText(tenantId) || fileId == null || !StringUtils.hasText(operationType) || !StringUtils.hasText(operationResult)) {
            return;
        }
        fileGovernanceMapper.insertFileAuditLog(params(
                "tenantId", tenantId,
                "fileId", fileId,
                "operationType", operationType,
                "operationResult", operationResult,
                "operatorType", defaultText(operatorType, "API"),
                "operatorId", operatorId,
                "traceId", traceId,
                "detailSummaryJson", toJson(detailSummary)
        ));
    }

    public Map<String, Object> operationDetail(String currentStatus,
                                               String nextStatus,
                                               String operatorId,
                                               String reason) {
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
