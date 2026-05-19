package com.example.batch.console.infrastructure.query;

import static com.example.batch.console.infrastructure.query.ConsoleQuerySupport.*;

import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.i18n.LocalizedErrorRenderer;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import com.example.batch.console.domain.entity.JobDefinitionEntity;
import com.example.batch.console.domain.entity.JobInstanceEntity;
import com.example.batch.console.domain.entity.JobPartitionEntity;
import com.example.batch.console.domain.entity.JobStepInstanceEntity;
import com.example.batch.console.domain.query.JobDefinitionQuery;
import com.example.batch.console.domain.query.JobInstanceQuery;
import com.example.batch.console.domain.query.JobPartitionQuery;
import com.example.batch.console.domain.query.JobStepInstanceQuery;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.support.querymap.ConsoleJobQueryMappers;
import com.example.batch.console.web.query.JobDefinitionQueryRequest;
import com.example.batch.console.web.query.JobInstanceQueryRequest;
import com.example.batch.console.web.query.JobPartitionQueryRequest;
import com.example.batch.console.web.query.JobStepInstanceQueryRequest;
import com.example.batch.console.web.response.job.ConsoleJobDefinitionResponse;
import com.example.batch.console.web.response.job.ConsoleJobInstanceResponse;
import com.example.batch.console.web.response.job.ConsoleJobPartitionResponse;
import com.example.batch.console.web.response.job.ConsoleJobStepInstanceResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 作业相关查询子服务。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConsoleJobQueryService {

  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleJobQueryMappers jobMappers;
  private final LocalizedErrorRenderer localizedErrorRenderer;
  private final BatchTimezoneProvider timezoneProvider;

  public PageResponse<ConsoleJobDefinitionResponse> jobDefinitions(
      JobDefinitionQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    JobDefinitionQuery query =
        new JobDefinitionQuery(
            resolveTenant(tenantGuard, request.getTenantId()),
            request.getJobCode(),
            request.getJobName(),
            request.getJobType(),
            request.getWorkerGroup(),
            request.getQueueCode(),
            request.getScheduleType(),
            request.getEnabled(),
            pageRequest);
    List<JobDefinitionEntity> rows = jobMappers.jobDefinitionMapper.selectByQuery(query);
    long total = jobMappers.jobDefinitionMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toJobDefinitionResponse);
  }

  public PageResponse<ConsoleJobInstanceResponse> jobInstances(JobInstanceQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    JobInstanceQuery query =
        new JobInstanceQuery(
            resolveTenant(tenantGuard, request.getTenantId()),
            request.getJobCode(),
            request.getInstanceStatus(),
            parseCsv(request.getInstanceStatuses()),
            request.getInstanceNo(),
            request.getBizDate(),
            request.getTraceId(),
            parseFlexibleInstant(
                request.getStartDate(), "startDate", timezoneProvider.defaultZone()),
            parseFlexibleInstantEndOfDay(
                request.getEndDate(), "endDate", timezoneProvider.defaultZone()),
            request.getSortBy(),
            request.getMinDurationSeconds(),
            request.getSlaBreached(),
            pageRequest);
    List<JobInstanceEntity> rows = jobMappers.jobInstanceMapper.selectByQuery(query);
    long total = jobMappers.jobInstanceMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toJobInstanceResponse);
  }

  public ConsoleJobInstanceResponse jobInstance(String tenantId, Long id) {
    JobInstanceEntity entity =
        jobMappers.jobInstanceMapper.selectById(resolveTenant(tenantGuard, tenantId), id);
    return toJobInstanceResponse(requireNotNull(entity, "job instance not found"));
  }

  public List<ConsoleJobInstanceResponse> batchInstanceStatus(
      String tenantId, List<String> instanceNos) {
    String resolved = resolveTenant(tenantGuard, tenantId);
    return jobMappers.jobInstanceMapper.selectByInstanceNos(resolved, instanceNos).stream()
        .map(this::toJobInstanceResponse)
        .toList();
  }

  public PageResponse<ConsoleJobStepInstanceResponse> jobStepInstances(
      JobStepInstanceQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    JobStepInstanceQuery query =
        new JobStepInstanceQuery(
            resolveTenant(tenantGuard, request.getTenantId()),
            request.getJobInstanceId(),
            request.getJobPartitionId(),
            request.getStepCode(),
            request.getStepStatus(),
            pageRequest);
    List<JobStepInstanceEntity> rows = jobMappers.jobStepInstanceMapper.selectByQuery(query);
    long total = jobMappers.jobStepInstanceMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toJobStepInstanceResponse);
  }

  public ConsoleJobStepInstanceResponse jobStepInstance(String tenantId, Long id) {
    JobStepInstanceEntity entity =
        jobMappers.jobStepInstanceMapper.selectById(resolveTenant(tenantGuard, tenantId), id);
    return toJobStepInstanceResponse(requireNotNull(entity, "job step instance not found"));
  }

  public PageResponse<ConsoleJobPartitionResponse> jobPartitions(JobPartitionQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    JobPartitionQuery query =
        new JobPartitionQuery(
            resolveTenant(tenantGuard, request.getTenantId()),
            request.getJobInstanceId(),
            request.getPartitionStatus(),
            pageRequest);
    List<JobPartitionEntity> rows = jobMappers.jobPartitionMapper.selectByQuery(query);
    long total = jobMappers.jobPartitionMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toJobPartitionResponse);
  }

  private ConsoleJobPartitionResponse toJobPartitionResponse(JobPartitionEntity entity) {
    return new ConsoleJobPartitionResponse(
        entity.getId(),
        display(entity.getTenantId()),
        entity.getJobInstanceId(),
        entity.getPartitionNo(),
        display(entity.getPartitionKey()),
        display(entity.getPartitionStatus()),
        display(entity.getWorkerGroup()),
        display(entity.getWorkerCode()),
        entity.getRetryCount(),
        display(entity.getBusinessKey()),
        entity.getLeaseExpireAt(),
        entity.getStartedAt(),
        entity.getFinishedAt());
  }

  private ConsoleJobDefinitionResponse toJobDefinitionResponse(JobDefinitionEntity entity) {
    return new ConsoleJobDefinitionResponse(
        entity.getId(),
        display(entity.getTenantId()),
        display(entity.getJobCode()),
        display(entity.getJobName()),
        display(entity.getJobType()),
        display(entity.getBizType()),
        display(entity.getQueueCode()),
        display(entity.getWorkerGroup()),
        display(entity.getScheduleType()),
        display(entity.getScheduleExpr()),
        display(entity.getTimezone()),
        display(entity.getCalendarCode()),
        display(entity.getWindowCode()),
        display(entity.getTriggerMode()),
        entity.getDagEnabled(),
        display(entity.getRetryPolicy()),
        entity.getRetryMaxCount(),
        entity.getTimeoutSeconds(),
        display(entity.getShardStrategy()),
        display(entity.getExecutionMode()),
        display(entity.getWatermarkField()),
        display(entity.getExecutionHandler()),
        display(entity.getParamSchema()),
        display(entity.getDefaultParams()),
        entity.getPriority(),
        entity.getVersion(),
        entity.getEnabled(),
        display(entity.getDescription()),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  private ConsoleJobInstanceResponse toJobInstanceResponse(JobInstanceEntity entity) {
    return new ConsoleJobInstanceResponse(
        entity.getId(),
        display(entity.getTenantId()),
        display(entity.getJobCode()),
        display(entity.getInstanceNo()),
        entity.getBizDate(),
        display(entity.getTriggerType()),
        display(entity.getInstanceStatus()),
        display(entity.getBatchNo()),
        display(entity.getOperatorId()),
        entity.getRerunFlag(),
        entity.getRetryFlag(),
        display(entity.getRerunReason()),
        entity.getRelatedFileId(),
        entity.getParentInstanceId(),
        display(entity.getQueueCode()),
        display(entity.getWorkerGroup()),
        entity.getPriority(),
        display(entity.getTraceId()),
        entity.getParamsSnapshot(),
        entity.getResultSummary(),
        entity.getDeadlineAt(),
        entity.getExpectedDurationSeconds(),
        entity.getSlaAlertedAt(),
        entity.getStartedAt(),
        entity.getFinishedAt(),
        entity.getDryRun(),
        display(entity.getFailureClass()));
  }

  private ConsoleJobStepInstanceResponse toJobStepInstanceResponse(JobStepInstanceEntity entity) {
    String errorMessage = localizedErrorRenderer.render(entity);
    return new ConsoleJobStepInstanceResponse(
        entity.getId(),
        display(entity.getTenantId()),
        entity.getJobInstanceId(),
        entity.getJobPartitionId(),
        entity.getJobTaskId(),
        display(entity.getStepCode()),
        display(entity.getStepType()),
        display(entity.getStepStatus()),
        entity.getRetryCount(),
        entity.getRelatedFileId(),
        entity.getResultSummary(),
        display(entity.getErrorCode()),
        display(errorMessage),
        entity.getStartedAt(),
        entity.getFinishedAt());
  }

  /** Comma-separated -> trimmed list,null/空 → null。供「多状态过滤」入口复用。 */
  private static List<String> parseCsv(String csv) {
    if (csv == null || csv.isBlank()) return null;
    List<String> out =
        java.util.Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    return out.isEmpty() ? null : out;
  }
}
