package com.example.batch.console.application;

import com.example.batch.console.domain.entity.DeadLetterTaskEntity;
import com.example.batch.console.domain.entity.FileRecordEntity;
import com.example.batch.console.domain.entity.JobInstanceEntity;
import com.example.batch.console.domain.entity.PendingCatchUpEntity;
import com.example.batch.console.domain.entity.RetryScheduleEntity;
import com.example.batch.console.domain.entity.WorkerRegistryEntity;
import com.example.batch.console.domain.query.AuditLogQuery;
import com.example.batch.console.domain.query.DeadLetterTaskQuery;
import com.example.batch.console.domain.query.FileRecordQuery;
import com.example.batch.console.domain.query.JobInstanceQuery;
import com.example.batch.console.domain.query.PendingCatchUpQuery;
import com.example.batch.console.domain.query.RetryScheduleQuery;
import com.example.batch.console.domain.query.WorkerRegistryQuery;
import com.example.batch.console.mapper.AuditLogMapper;
import com.example.batch.console.mapper.DeadLetterTaskMapper;
import com.example.batch.console.mapper.FileChannelConfigMapper;
import com.example.batch.console.mapper.FileDispatchRecordMapper;
import com.example.batch.console.mapper.FilePipelineMapper;
import com.example.batch.console.mapper.FilePipelineStepRunMapper;
import com.example.batch.console.mapper.FileRecordMapper;
import com.example.batch.console.mapper.FileTemplateConfigMapper;
import com.example.batch.console.mapper.JobInstanceMapper;
import com.example.batch.console.mapper.PendingCatchUpMapper;
import com.example.batch.console.mapper.RetryScheduleMapper;
import com.example.batch.console.mapper.WorkerRegistryMapper;
import com.example.batch.console.web.query.AuditLogQueryRequest;
import com.example.batch.console.web.query.DeadLetterQueryRequest;
import com.example.batch.console.web.query.FileChannelQueryRequest;
import com.example.batch.console.web.query.FileChainQueryRequest;
import com.example.batch.console.web.query.FileDispatchRecordQueryRequest;
import com.example.batch.console.web.query.FilePipelineQueryRequest;
import com.example.batch.console.web.query.FilePipelineStepQueryRequest;
import com.example.batch.console.web.query.FileTemplateQueryRequest;
import com.example.batch.console.web.query.JobInstanceQueryRequest;
import com.example.batch.console.web.query.PendingCatchUpQueryRequest;
import com.example.batch.console.web.query.RetryScheduleQueryRequest;
import com.example.batch.console.web.query.WorkerRegistryQueryRequest;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.model.PageRequest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DefaultConsoleQueryApplicationService implements ConsoleQueryApplicationService {

    private final AuditLogMapper auditLogMapper;
    private final JobInstanceMapper jobInstanceMapper;
    private final FileRecordMapper fileRecordMapper;
    private final FilePipelineMapper filePipelineMapper;
    private final FilePipelineStepRunMapper filePipelineStepRunMapper;
    private final FileDispatchRecordMapper fileDispatchRecordMapper;
    private final FileChannelConfigMapper fileChannelConfigMapper;
    private final FileTemplateConfigMapper fileTemplateConfigMapper;
    private final DeadLetterTaskMapper deadLetterTaskMapper;
    private final RetryScheduleMapper retryScheduleMapper;
    private final PendingCatchUpMapper pendingCatchUpMapper;
    private final WorkerRegistryMapper workerRegistryMapper;

    @Override
    public List<Map<String, Object>> auditLogs(AuditLogQueryRequest request) {
        AuditLogQuery query = new AuditLogQuery();
        query.setTenantId(request.getTenantId());
        query.setOperationType(request.getOperationType());
        query.setTraceId(request.getTraceId());
        query.setFromTime(parseInstant(request.getFromTime(), "fromTime"));
        query.setToTime(parseInstant(request.getToTime(), "toTime"));
        return auditLogMapper.selectByQuery(query);
    }

    @Override
    public List<FileRecordEntity> fileChains(FileChainQueryRequest request) {
        FileRecordQuery query = new FileRecordQuery(
                request.getTenantId(),
                request.getBizType() == null || request.getBizType().isBlank() ? request.getPipelineType() : request.getBizType(),
                request.getFileStatus(),
                parseLong(request.getFileId(), "fileId"),
                request.getFileName(),
                request.getTraceId(),
                parseInstant(request.getFromTime(), "fromTime"),
                parseInstant(request.getToTime(), "toTime"),
                new PageRequest(1, 20)
        );
        return fileRecordMapper.selectByQuery(query);
    }

    @Override
    public List<Map<String, Object>> filePipelines(FilePipelineQueryRequest request) {
        return filePipelineMapper.selectByQuery(
                request.getTenantId(),
                request.getFileId(),
                request.getPipelineInstanceId(),
                request.getPipelineType(),
                request.getRunStatus(),
                request.getTraceId(),
                parseInstant(request.getFromTime(), "fromTime"),
                parseInstant(request.getToTime(), "toTime")
        );
    }

    @Override
    public List<Map<String, Object>> filePipelineSteps(FilePipelineStepQueryRequest request) {
        return filePipelineStepRunMapper.selectByQuery(
                request.getPipelineInstanceId(),
                request.getStepCode(),
                request.getStageCode(),
                request.getStepStatus()
        );
    }

    @Override
    public List<Map<String, Object>> fileDispatchRecords(FileDispatchRecordQueryRequest request) {
        return fileDispatchRecordMapper.selectByQuery(
                request.getTenantId(),
                request.getFileId(),
                request.getChannelCode(),
                request.getDispatchStatus(),
                request.getReceiptStatus(),
                parseInstant(request.getFromTime(), "fromTime"),
                parseInstant(request.getToTime(), "toTime")
        );
    }

    @Override
    public List<Map<String, Object>> fileChannels(FileChannelQueryRequest request) {
        return fileChannelConfigMapper.selectByQuery(
                request.getTenantId(),
                request.getChannelCode(),
                request.getChannelType(),
                request.getEnabled()
        );
    }

    @Override
    public List<Map<String, Object>> fileTemplates(FileTemplateQueryRequest request) {
        return fileTemplateConfigMapper.selectByQuery(
                request.getTenantId(),
                request.getTemplateCode(),
                request.getTemplateType(),
                request.getEnabled()
        );
    }

    @Override
    public List<JobInstanceEntity> jobInstances(JobInstanceQueryRequest request) {
        JobInstanceQuery query = new JobInstanceQuery(
                request.getTenantId(),
                request.getJobCode(),
                request.getInstanceStatus(),
                new PageRequest(1, 20)
        );
        return jobInstanceMapper.selectByQuery(query);
    }

    @Override
    public List<DeadLetterTaskEntity> deadLetters(DeadLetterQueryRequest request) {
        return deadLetterTaskMapper.selectByQuery(new DeadLetterTaskQuery(
                request.getTenantId(),
                request.getSourceType(),
                request.getReplayStatus(),
                request.getTraceId()
        ));
    }

    @Override
    public List<RetryScheduleEntity> retries(RetryScheduleQueryRequest request) {
        return retryScheduleMapper.selectByQuery(new RetryScheduleQuery(
                request.getTenantId(),
                request.getRelatedType(),
                request.getRetryPolicy(),
                request.getRetryStatus()
        ));
    }

    @Override
    public List<PendingCatchUpEntity> pendingCatchUps(PendingCatchUpQueryRequest request) {
        return pendingCatchUpMapper.selectByQuery(new PendingCatchUpQuery(
                request.getTenantId(),
                request.getJobCode(),
                request.getRequestId()
        ));
    }

    @Override
    public List<WorkerRegistryEntity> workers(WorkerRegistryQueryRequest request) {
        return workerRegistryMapper.selectByQuery(new WorkerRegistryQuery(
                request.getTenantId(),
                request.getWorkerGroup(),
                request.getStatus()
        ));
    }

    private Instant parseInstant(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, fieldName + " must be ISO-8601 datetime");
        }
    }

    private Long parseLong(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, fieldName + " must be a number");
        }
    }
}
