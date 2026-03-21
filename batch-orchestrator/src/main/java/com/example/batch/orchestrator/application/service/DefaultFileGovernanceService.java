package com.example.batch.orchestrator.application.service;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import com.example.batch.orchestrator.domain.command.FileGovernanceCommand;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.query.JobTaskQuery;
import com.example.batch.orchestrator.infrastructure.file.FileGovernanceRepository;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import java.util.Comparator;
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
        jobPartitionMapper.resetForDispatch(command.tenantId(), partition.getId());
        jobTaskMapper.resetForRetry(command.tenantId(), task.getId());
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

    private String resolveOperatorType(String operatorId) {
        return StringUtils.hasText(operatorId) ? "USER" : "API";
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
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
}
