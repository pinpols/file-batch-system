package com.example.batch.console.application;

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
import com.example.batch.console.domain.entity.DeadLetterTaskEntity;
import com.example.batch.console.domain.entity.FileRecordEntity;
import com.example.batch.console.domain.entity.JobInstanceEntity;
import com.example.batch.console.domain.entity.PendingCatchUpEntity;
import com.example.batch.console.domain.entity.RetryScheduleEntity;
import com.example.batch.console.domain.entity.WorkerRegistryEntity;
import java.util.List;
import java.util.Map;

public interface ConsoleQueryApplicationService {

    List<Map<String, Object>> auditLogs(AuditLogQueryRequest request);

    List<FileRecordEntity> fileChains(FileChainQueryRequest request);

    List<Map<String, Object>> filePipelines(FilePipelineQueryRequest request);

    List<Map<String, Object>> filePipelineSteps(FilePipelineStepQueryRequest request);

    List<Map<String, Object>> fileDispatchRecords(FileDispatchRecordQueryRequest request);

    List<Map<String, Object>> fileChannels(FileChannelQueryRequest request);

    List<Map<String, Object>> fileTemplates(FileTemplateQueryRequest request);

    List<JobInstanceEntity> jobInstances(JobInstanceQueryRequest request);

    List<DeadLetterTaskEntity> deadLetters(DeadLetterQueryRequest request);

    List<RetryScheduleEntity> retries(RetryScheduleQueryRequest request);

    List<PendingCatchUpEntity> pendingCatchUps(PendingCatchUpQueryRequest request);

    List<WorkerRegistryEntity> workers(WorkerRegistryQueryRequest request);
}
