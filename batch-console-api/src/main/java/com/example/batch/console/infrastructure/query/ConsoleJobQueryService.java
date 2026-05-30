package com.example.batch.console.infrastructure.query;

import static com.example.batch.console.domain.observability.infrastructure.ConsoleQuerySupport.*;

import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.i18n.LocalizedErrorRenderer;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import com.example.batch.console.domain.job.entity.JobDefinitionEntity;
import com.example.batch.console.domain.job.entity.JobExecutionLogEntity;
import com.example.batch.console.domain.job.entity.JobInstanceEntity;
import com.example.batch.console.domain.job.entity.JobPartitionEntity;
import com.example.batch.console.domain.job.entity.JobStepInstanceEntity;
import com.example.batch.console.domain.job.query.JobDefinitionQuery;
import com.example.batch.console.domain.job.query.JobExecutionLogQuery;
import com.example.batch.console.domain.job.query.JobInstanceQuery;
import com.example.batch.console.domain.job.query.JobPartitionQuery;
import com.example.batch.console.domain.job.query.JobStepInstanceQuery;
import com.example.batch.console.domain.job.support.ConsoleJobQueryMappers;
import com.example.batch.console.domain.job.web.query.JobDefinitionQueryRequest;
import com.example.batch.console.domain.job.web.query.JobExecutionLogQueryRequest;
import com.example.batch.console.domain.job.web.query.JobInstanceQueryRequest;
import com.example.batch.console.domain.job.web.query.JobPartitionQueryRequest;
import com.example.batch.console.domain.job.web.query.JobStepInstanceQueryRequest;
import com.example.batch.console.domain.job.web.response.ConsoleJobDefinitionResponse;
import com.example.batch.console.domain.job.web.response.ConsoleJobExecutionLogResponse;
import com.example.batch.console.domain.job.web.response.ConsoleJobInstanceResponse;
import com.example.batch.console.domain.job.web.response.ConsoleJobPartitionResponse;
import com.example.batch.console.domain.job.web.response.ConsoleJobStepInstanceResponse;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import java.util.Arrays;
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

  /**
   * 任务级执行日志查看(P0):查 {@code batch.job_execution_log},锚定单个 jobInstanceId,支持 level/type/keyword 过滤 +
   * 双轨分页。大表 + 时间序,优先 cursor 模式(传 cursor 即生效,不查 count)。
   */
  public PageResponse<ConsoleJobExecutionLogResponse> jobExecutionLogs(
      JobExecutionLogQueryRequest request) {
    boolean cursorMode = request.getCursor() != null && !request.getCursor().isBlank();
    PageRequest pageRequest =
        cursorMode
            ? new PageRequest(1, request.getPageSize())
            : new PageRequest(request.getPageNo(), request.getPageSize());
    JobExecutionLogQuery query =
        new JobExecutionLogQuery(
            resolveTenant(tenantGuard, request.getTenantId()),
            request.getJobInstanceId(),
            request.getJobPartitionId(),
            request.getLogLevel(),
            request.getLogType(),
            request.getKeyword(),
            pageRequest,
            decodeCursorId(request.getCursor()));
    List<JobExecutionLogEntity> rows = jobMappers.jobExecutionLogMapper.selectByQuery(query);
    if (cursorMode) {
      return cursorPage(
          pageRequest, rows, this::toJobExecutionLogResponse, JobExecutionLogEntity::getId);
    }
    long total = jobMappers.jobExecutionLogMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toJobExecutionLogResponse);
  }

  private ConsoleJobExecutionLogResponse toJobExecutionLogResponse(JobExecutionLogEntity entity) {
    return new ConsoleJobExecutionLogResponse(
        entity.getId(),
        display(entity.getTenantId()),
        entity.getJobInstanceId(),
        entity.getJobPartitionId(),
        display(entity.getLogLevel()),
        display(entity.getLogType()),
        display(entity.getTraceId()),
        display(entity.getMessage()),
        display(entity.getDetailRef()),
        entity.getExtraJson(),
        entity.getCreatedAt());
  }

  public PageResponse<ConsoleJobInstanceResponse> jobInstances(JobInstanceQueryRequest request) {
    boolean cursorMode = request.getCursor() != null && !request.getCursor().isBlank();
    // ADR-031:cursor 模式 cursor key 是 id,与 sortBy=duration 互斥(duration 排序 cursor key 应该是 duration)
    // 不静默忽略 sortBy,直接拒绝,避免「客户端以为按 duration 翻页实际按 id」的隐性 bug
    if (cursorMode && "duration".equals(request.getSortBy())) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "sortBy=duration is not supported in cursor mode; remove cursor or use pageNo");
    }
    // cursor 模式忽略 pageNo,统一 pageNo=1(防止意外 OFFSET);其它字段保留以拼 WHERE
    PageRequest pageRequest =
        cursorMode
            ? new PageRequest(1, request.getPageSize())
            : new PageRequest(request.getPageNo(), request.getPageSize());
    Long cursorId = decodeCursorId(request.getCursor());
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
            pageRequest,
            cursorId);
    List<JobInstanceEntity> rows = jobMappers.jobInstanceMapper.selectByQuery(query);
    if (cursorMode) {
      return cursorPage(pageRequest, rows, this::toJobInstanceResponse, JobInstanceEntity::getId);
    }
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
    boolean cursorMode = request.getCursor() != null && !request.getCursor().isBlank();
    PageRequest pageRequest =
        cursorMode
            ? new PageRequest(1, request.getPageSize())
            : new PageRequest(request.getPageNo(), request.getPageSize());
    JobStepInstanceQuery query =
        new JobStepInstanceQuery(
            resolveTenant(tenantGuard, request.getTenantId()),
            request.getJobInstanceId(),
            request.getJobPartitionId(),
            request.getStepCode(),
            request.getStepStatus(),
            pageRequest,
            decodeCursorId(request.getCursor()));
    List<JobStepInstanceEntity> rows = jobMappers.jobStepInstanceMapper.selectByQuery(query);
    if (cursorMode) {
      return cursorPage(
          pageRequest, rows, this::toJobStepInstanceResponse, JobStepInstanceEntity::getId);
    }
    long total = jobMappers.jobStepInstanceMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toJobStepInstanceResponse);
  }

  public ConsoleJobStepInstanceResponse jobStepInstance(String tenantId, Long id) {
    JobStepInstanceEntity entity =
        jobMappers.jobStepInstanceMapper.selectById(resolveTenant(tenantGuard, tenantId), id);
    return toJobStepInstanceResponse(requireNotNull(entity, "job step instance not found"));
  }

  public PageResponse<ConsoleJobPartitionResponse> jobPartitions(JobPartitionQueryRequest request) {
    boolean cursorMode = request.getCursor() != null && !request.getCursor().isBlank();
    PageRequest pageRequest =
        cursorMode
            ? new PageRequest(1, request.getPageSize())
            : new PageRequest(request.getPageNo(), request.getPageSize());
    JobPartitionQuery query =
        new JobPartitionQuery(
            resolveTenant(tenantGuard, request.getTenantId()),
            request.getJobInstanceId(),
            request.getPartitionStatus(),
            pageRequest,
            decodeCursorId(request.getCursor()));
    List<JobPartitionEntity> rows = jobMappers.jobPartitionMapper.selectByQuery(query);
    if (cursorMode) {
      return cursorPage(pageRequest, rows, this::toJobPartitionResponse, JobPartitionEntity::getId);
    }
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
        Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    return out.isEmpty() ? null : out;
  }
}
