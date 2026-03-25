package com.example.batch.orchestrator.application.service;

import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import com.example.batch.orchestrator.config.FileGovernanceProperties;
import com.example.batch.orchestrator.domain.command.ArrivalGroupGovernanceCommand;
import com.example.batch.orchestrator.domain.command.FileGovernanceCommand;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.query.JobTaskQuery;
import com.example.batch.orchestrator.infrastructure.file.FileGovernanceRepository;
import com.example.batch.orchestrator.infrastructure.file.MinioGovernanceStorage;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import java.util.Comparator;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class DefaultFileGovernanceService implements FileGovernanceService {

    private final FileGovernanceRepository fileGovernanceRepository;
    private final JobTaskMapper jobTaskMapper;
    private final JobPartitionMapper jobPartitionMapper;
    private final JobInstanceMapper jobInstanceMapper;
    private final TaskDispatchOutboxService taskDispatchOutboxService;
    private final FileGovernanceProperties fileGovernanceProperties;
    private final MinioGovernanceStorage minioGovernanceStorage;
    private final BatchSecurityProperties batchSecurityProperties;

    @Override
    @Transactional
    public String archiveFile(FileGovernanceCommand command) {
        return changeFileStatus(command, "ARCHIVED", "ARCHIVE");
    }

    @Override
    @Transactional
    public String deleteFile(FileGovernanceCommand command) {
        return changeFileStatus(command, "DELETED", "DELETE");
    }

    @Override
    @Transactional
    public String presignFileDownload(FileGovernanceCommand command) {
        validateCommand(command);
        Map<String, Object> fileRecord = fileGovernanceRepository.loadFileRecord(command.tenantId(), command.fileId());
        if (fileRecord.isEmpty()) {
            throw new BizException(ResultCode.NOT_FOUND, "file record not found");
        }
        Map<String, Object> security = fileGovernanceRepository.loadTemplateSecurityForFile(command.tenantId(), command.fileId());
        if (requiresDownloadApproval(security) && !StringUtils.hasText(command.approvalId())) {
            throw new BizException(ResultCode.BUSINESS_ERROR, "approvalId is required for download on this file template");
        }
        if (truthy(security.get("content_encryption_enabled")) && !batchSecurityProperties.isTestingOpen()) {
            String consolePath = "/api/console/files/" + command.fileId() + "/download?tenantId=" + command.tenantId();
            if (StringUtils.hasText(command.approvalId())) {
                consolePath += "&approvalId=" + command.approvalId();
            }
            Map<String, Object> auditDetail = new LinkedHashMap<>();
            auditDetail.put("storageBucket", fileRecord.get("storage_bucket"));
            auditDetail.put("storagePath", fileRecord.get("storage_path"));
            auditDetail.put("approvalId", command.approvalId());
            auditDetail.put("contentEncryptionEnabled", true);
            auditDetail.put("encryptionKeyRef", security.get("encryption_key_ref"));
            fileGovernanceRepository.appendAudit(
                    command.tenantId(),
                    command.fileId(),
                    "PRESIGN_DOWNLOAD",
                    "SUCCESS",
                    resolveOperatorType(command.operatorId()),
                    command.operatorId(),
                    command.traceId(),
                    auditDetail
            );
            return consolePath;
        }
        String storagePath = stringValue(fileRecord.get("storage_path"));
        String storageBucket = stringValue(fileRecord.get("storage_bucket"));
        if (storagePath == null || storagePath.isBlank()) {
            throw new BizException(ResultCode.STATE_CONFLICT, "file storage path is missing");
        }
        int expirySeconds = Math.max(60, fileGovernanceProperties.getAccess().getPresignExpirySeconds());
        String presignedUrl = minioGovernanceStorage.createPresignedDownloadUrl(storageBucket, storagePath, expirySeconds);
        Map<String, Object> auditDetail = new LinkedHashMap<>();
        auditDetail.put("storageBucket", storageBucket);
        auditDetail.put("storagePath", storagePath);
        auditDetail.put("expirySeconds", expirySeconds);
        auditDetail.put("approvalId", command.approvalId());
        auditDetail.put("downloadRequiresApproval", truthy(security.get("download_requires_approval")));
        auditDetail.put("contentEncryptionEnabled", truthy(security.get("content_encryption_enabled")));
        auditDetail.put("encryptionKeyRef", security.get("encryption_key_ref"));
        auditDetail.put("maskingRuleSet", security.get("masking_rule_set"));
        if (truthy(security.get("preview_masking_enabled"))) {
            auditDetail.put("previewMaskingNote", "template enables preview masking; avoid exposing raw object bytes in UI");
        }
        fileGovernanceRepository.appendAudit(
                command.tenantId(),
                command.fileId(),
                "PRESIGN_DOWNLOAD",
                "SUCCESS",
                resolveOperatorType(command.operatorId()),
                command.operatorId(),
                command.traceId(),
                auditDetail
        );
        return presignedUrl;
    }

    @Override
    @Transactional
    public String redispatchFile(FileGovernanceCommand command) {
        validateCommand(command);
        Map<String, Object> fileRecord = fileGovernanceRepository.loadFileRecord(command.tenantId(), command.fileId());
        if (fileRecord.isEmpty()) {
            throw new BizException(ResultCode.NOT_FOUND, "file record not found");
        }
        Map<String, Object> dispatchRecord = fileGovernanceRepository.loadLatestDispatchRecord(
                command.tenantId(),
                command.fileId(),
                command.channelCode()
        );
        if (dispatchRecord.isEmpty()) {
            throw new BizException(ResultCode.NOT_FOUND, "dispatch record not found");
        }
        Long pipelineInstanceId = toLong(dispatchRecord.get("pipeline_instance_id"));
        Long relatedJobInstanceId = fileGovernanceRepository.loadRelatedJobInstanceId(pipelineInstanceId);
        if (relatedJobInstanceId == null) {
            throw new BizException(ResultCode.STATE_CONFLICT, "dispatch pipeline is not bound to a job instance");
        }
        JobInstanceEntity jobInstance = jobInstanceMapper.selectById(command.tenantId(), relatedJobInstanceId);
        if (jobInstance == null) {
            throw new BizException(ResultCode.NOT_FOUND, "dispatch job instance not found");
        }
        JobTaskEntity task = resolveDispatchTask(command.tenantId(), jobInstance.getId());
        JobPartitionEntity partition = jobPartitionMapper.selectById(command.tenantId(), task.getJobPartitionId());
        if (partition == null) {
            throw new BizException(ResultCode.NOT_FOUND, "dispatch partition not found");
        }

        fileGovernanceRepository.resetDispatchRecordForRedispatch(command.tenantId(), toLong(dispatchRecord.get("id")));
        jobPartitionMapper.resetForDispatch(command.tenantId(), partition.getId(), PartitionStatus.READY.code());
        jobTaskMapper.resetForRetry(command.tenantId(), task.getId(), TaskStatus.READY.code());
        taskDispatchOutboxService.writeDispatchEvent(
                jobInstance,
                task,
                partition,
                command.traceId(),
                command.tenantId() + ":manual-redispatch:" + task.getId()
        );
        fileGovernanceRepository.appendAudit(
                command.tenantId(),
                command.fileId(),
                "REDISPATCH",
                "SUCCESS",
                resolveOperatorType(command.operatorId()),
                command.operatorId(),
                command.traceId(),
                buildRedispatchDetail(dispatchRecord, task, partition, command)
        );
        return "REDISPATCH_ACCEPTED";
    }

    @Override
    @Transactional
    public String operateArrivalGroup(ArrivalGroupGovernanceCommand command) {
        validateArrivalGroupCommand(command);
        List<Map<String, Object>> groupFiles = fileGovernanceRepository.selectArrivalGroupFiles(command.tenantId(), command.fileGroupCode());
        if (groupFiles.isEmpty()) {
            throw new BizException(ResultCode.NOT_FOUND, "arrival group not found");
        }
        Instant now = Instant.now();
        String action = command.action().trim().toUpperCase();
        String nextState = switch (action) {
            case "CONTINUE_WAITING" -> "WAITING_ARRIVAL";
            case "SKIP_BATCH" -> "TIMEOUT";
            case "EMPTY_RUN", "TRIGGER_NOW" -> "TRIGGERED";
            default -> throw new BizException(ResultCode.INVALID_ARGUMENT, "unsupported arrival action: " + command.action());
        };
        if ("EMPTY_RUN".equals(action) && !toBoolean(groupFiles.get(0).get("allow_empty_run"))) {
            throw new BizException(ResultCode.STATE_CONFLICT, "arrival group does not allow empty run");
        }
        if ("SKIP_BATCH".equals(action) && !toBoolean(groupFiles.get(0).get("allow_skip_biz_date"))) {
            throw new BizException(ResultCode.STATE_CONFLICT, "arrival group does not allow skip batch");
        }
        long extensionSeconds = command.extendWaitSeconds() == null || command.extendWaitSeconds() <= 0
                ? fileGovernanceProperties.getArrival().getManualWaitExtensionSeconds()
                : command.extendWaitSeconds();
        String latestTolerableTime = "CONTINUE_WAITING".equals(action)
                ? now.plusSeconds(Math.max(1L, extensionSeconds)).toString()
                : stringValue(groupFiles.get(0).get("latest_tolerable_time"));
        for (Map<String, Object> groupFile : groupFiles) {
            Long fileId = toLong(groupFile.get("id"));
            if (fileId == null) {
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("arrivalState", nextState);
            metadata.put("arrivalReason", "MANUAL_" + action);
            metadata.put("arrivalCheckedAt", now.toString());
            metadata.put("manualArrivalAction", action);
            metadata.put("manualArrivalActionAt", now.toString());
            metadata.put("manualArrivalOperatorId", command.operatorId());
            metadata.put("manualArrivalReason", command.reason());
            if ("CONTINUE_WAITING".equals(action)) {
                metadata.put("latestTolerableTime", latestTolerableTime);
            }
            if ("TRIGGERED".equals(nextState)) {
                metadata.put("arrivalTriggeredAt", now.toString());
            }
            if ("TIMEOUT".equals(nextState)) {
                metadata.put("arrivalTimedOutAt", now.toString());
            }
            fileGovernanceRepository.updateFileMetadata(command.tenantId(), fileId, metadata);
            fileGovernanceRepository.appendAudit(
                    command.tenantId(),
                    fileId,
                    "ARRIVAL_MANUAL_" + action,
                    "SUCCESS",
                    resolveOperatorType(command.operatorId()),
                    command.operatorId(),
                    command.traceId(),
                    metadata
            );
        }
        return nextState;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private boolean requiresDownloadApproval(Map<String, Object> security) {
        if (batchSecurityProperties.isTestingOpen()) {
            return false;
        }
        if (security == null || security.isEmpty()) {
            return false;
        }
        return truthy(security.get("download_requires_approval")) || truthy(security.get("content_encryption_enabled"));
    }

    /**
     * 文件治理只允许在运行态安静时改状态，避免和 pipeline/dispatch 并发写冲突。
     */
    private String changeFileStatus(FileGovernanceCommand command, String nextStatus, String operationType) {
        validateCommand(command);
        Map<String, Object> fileRecord = fileGovernanceRepository.loadFileRecord(command.tenantId(), command.fileId());
        if (fileRecord.isEmpty()) {
            throw new BizException(ResultCode.NOT_FOUND, "file record not found");
        }
        String currentStatus = stringValue(fileRecord.get("file_status"));
        try {
            assertNoActiveRuntime(command);
            fileGovernanceRepository.updateFileStatus(
                    command.tenantId(),
                    command.fileId(),
                    nextStatus,
                    Map.of(
                            "governanceOperation", operationType,
                            "reason", command.reason(),
                            "operatorId", command.operatorId()
                    )
            );
            fileGovernanceRepository.appendAudit(
                    command.tenantId(),
                    command.fileId(),
                    operationType,
                    "SUCCESS",
                    resolveOperatorType(command.operatorId()),
                    command.operatorId(),
                    command.traceId(),
                    fileGovernanceRepository.operationDetail(currentStatus, nextStatus, command.operatorId(), command.reason())
            );
            return nextStatus;
        } catch (RuntimeException exception) {
            fileGovernanceRepository.appendAudit(
                    command.tenantId(),
                    command.fileId(),
                    operationType,
                    "FAILED",
                    resolveOperatorType(command.operatorId()),
                    command.operatorId(),
                    command.traceId(),
                    Map.of(
                            "currentStatus", currentStatus,
                            "nextStatus", nextStatus,
                            "reason", command.reason(),
                            "errorMessage", exception.getMessage()
                    )
            );
            throw exception;
        }
    }

    private JobTaskEntity resolveDispatchTask(String tenantId, Long jobInstanceId) {
        List<JobTaskEntity> tasks = jobTaskMapper.selectByQuery(new JobTaskQuery(
                tenantId,
                jobInstanceId,
                null,
                null,
                null
        ));
        return tasks.stream()
                .filter(task -> "DISPATCH".equalsIgnoreCase(task.getTaskType()))
                .sorted(Comparator.comparing(JobTaskEntity::getTaskSeq, Comparator.nullsLast(Integer::compareTo)))
                .findFirst()
                .orElseThrow(() -> new BizException(ResultCode.NOT_FOUND, "dispatch task not found"));
    }

    private Map<String, Object> buildRedispatchDetail(Map<String, Object> dispatchRecord,
                                                      JobTaskEntity task,
                                                      JobPartitionEntity partition,
                                                      FileGovernanceCommand command) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("dispatchRecordId", dispatchRecord.get("id"));
        detail.put("channelCode", dispatchRecord.get("channel_code"));
        detail.put("taskId", task.getId());
        detail.put("partitionId", partition.getId());
        detail.put("reason", command.reason());
        return detail;
    }

    private void assertNoActiveRuntime(FileGovernanceCommand command) {
        long activePipelines = fileGovernanceRepository.countActivePipelineInstances(command.tenantId(), command.fileId());
        if (activePipelines > 0) {
            throw new BizException(ResultCode.STATE_CONFLICT, "file still has active pipeline instances");
        }
        long pendingDispatches = fileGovernanceRepository.countPendingDispatchRecords(command.tenantId(), command.fileId());
        if (pendingDispatches > 0) {
            throw new BizException(ResultCode.STATE_CONFLICT, "file still has pending dispatch records");
        }
    }

    private void validateCommand(FileGovernanceCommand command) {
        if (command == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "file governance command is required");
        }
        if (!StringUtils.hasText(command.tenantId())) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "tenantId is required");
        }
        if (command.fileId() == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "fileId is required");
        }
    }

    private void validateArrivalGroupCommand(ArrivalGroupGovernanceCommand command) {
        if (command == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "arrival group command is required");
        }
        if (!StringUtils.hasText(command.tenantId())) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "tenantId is required");
        }
        if (!StringUtils.hasText(command.fileGroupCode())) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "fileGroupCode is required");
        }
        if (!StringUtils.hasText(command.action())) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "action is required");
        }
    }

    private String resolveOperatorType(String operatorId) {
        return StringUtils.hasText(operatorId) ? "USER" : "API";
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

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }
}
