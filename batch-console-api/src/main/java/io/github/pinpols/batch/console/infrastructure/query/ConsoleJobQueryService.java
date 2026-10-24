package io.github.pinpols.batch.console.infrastructure.query;

import static io.github.pinpols.batch.console.shared.query.ConsoleQuerySupport.*;

import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.i18n.LocalizedErrorRenderer;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Nullables;
import io.github.pinpols.batch.common.utils.SecretMasking;
import io.github.pinpols.batch.console.domain.job.application.contract.query.JobDefinitionQueryRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.query.JobExecutionLogQueryRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.query.JobInstanceQueryRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.query.JobPartitionQueryRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.query.JobStepInstanceQueryRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobDefinitionResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobExecutionLogResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobInstanceResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobPartitionResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobStepInstanceResponse;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.console.domain.job.entity.JobInstanceEntity;
import io.github.pinpols.batch.console.domain.job.entity.JobPartitionEntity;
import io.github.pinpols.batch.console.domain.job.entity.JobStepInstanceEntity;
import io.github.pinpols.batch.console.domain.job.query.JobDefinitionQuery;
import io.github.pinpols.batch.console.domain.job.query.JobExecutionLogQuery;
import io.github.pinpols.batch.console.domain.job.query.JobInstanceQuery;
import io.github.pinpols.batch.console.domain.job.query.JobPartitionQuery;
import io.github.pinpols.batch.console.domain.job.query.JobStepInstanceQuery;
import io.github.pinpols.batch.console.domain.job.support.ConsoleJobQueryMappers;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
    String requestedTenantId = request.getTenantId();
    String tenantId = resolveTenant(tenantGuard, requestedTenantId);
    JobDefinitionQuery query = new JobDefinitionQuery(
        tenantId,
        request.getJobCode(),
        request.getExactJobCode(),
        request.getDependsOnJobCode(),
        request.getJobName(),
        request.getJobType(),
        request.getWorkerGroup(),
        request.getQueueCode(),
        request.getScheduleType(),
        request.getTriggerMode(),
        request.getHasUpstreamDependency(),
        request.getMonitoringCategory(),
        Boolean.TRUE.equals(request.getIncludeDisabled()) ? null : request.getEnabled(),
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
    boolean cursorMode = EmptyChecks.isNotNull(request.getCursor());
    PageRequest pageRequest = cursorMode
        ? new PageRequest(1, request.getPageSize())
        : new PageRequest(request.getPageNo(), request.getPageSize());
    String requestedTenantId = request.getTenantId();
    String tenantId = resolveTenant(tenantGuard, requestedTenantId);
    Long jobInstanceId = request.getJobInstanceId();
    Long jobPartitionId = request.getJobPartitionId();
    String logLevel = request.getLogLevel();
    String logType = request.getLogType();
    String traceId = request.getTraceId();
    String keyword = request.getKeyword();
    Long cursorId = decodeCursorId(request.getCursor());
    JobExecutionLogQuery query = new JobExecutionLogQuery(
        tenantId,
        jobInstanceId,
        jobPartitionId,
        logLevel,
        logType,
        traceId,
        keyword,
        pageRequest,
        cursorId);
    List<JobExecutionLogEntity> rows = jobMappers.jobExecutionLogMapper.selectByQuery(query);
    if (cursorMode) {
      return cursorPage(
          pageRequest, rows, this::toJobExecutionLogResponse, JobExecutionLogEntity::getId);
    }
    long total = jobMappers.jobExecutionLogMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toJobExecutionLogResponse);
  }

  private ConsoleJobExecutionLogResponse toJobExecutionLogResponse(JobExecutionLogEntity entity) {
    Long id = entity.getId();
    String tenantId = display(entity.getTenantId());
    Long jobInstanceId = entity.getJobInstanceId();
    Long jobPartitionId = entity.getJobPartitionId();
    String logLevel = display(entity.getLogLevel());
    String logType = display(entity.getLogType());
    String traceId = display(entity.getTraceId());
    String message = display(entity.getMessage());
    String detailRef = display(entity.getDetailRef());
    String rawExtraJson = entity.getExtraJson();
    String extraJson = SecretMasking.maskSensitiveJson(rawExtraJson);
    Instant createdAt = entity.getCreatedAt();
    return new ConsoleJobExecutionLogResponse(
        id,
        tenantId,
        jobInstanceId,
        jobPartitionId,
        logLevel,
        logType,
        traceId,
        message,
        detailRef,
        extraJson,
        createdAt);
  }

  public PageResponse<ConsoleJobInstanceResponse> jobInstances(JobInstanceQueryRequest request) {
    boolean cursorMode = EmptyChecks.isNotNull(request.getCursor());
    // ADR-031:cursor 模式 cursor key 是 id,与 sortBy=duration 互斥(duration 排序 cursor key 应该是 duration)
    // 不静默忽略 sortBy,直接拒绝,避免「客户端以为按 duration 翻页实际按 id」的隐性 bug
    if (cursorMode && "duration".equals(request.getSortBy())) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ResultCode.INVALID_ARGUMENT.detailKey(),
          "sortBy=duration is not supported in cursor mode; remove cursor or use pageNo");
    }
    // cursor 模式忽略 pageNo,统一 pageNo=1(防止意外 OFFSET);其它字段保留以拼 WHERE
    PageRequest pageRequest = cursorMode
        ? new PageRequest(1, request.getPageSize())
        : new PageRequest(request.getPageNo(), request.getPageSize());
    Long cursorId = decodeCursorId(request.getCursor());
    String requestedTenantId = request.getTenantId();
    String tenantId = resolveTenant(tenantGuard, requestedTenantId);
    String jobCode = request.getJobCode();
    String instanceStatus = request.getInstanceStatus();
    String instanceStatusesText = request.getInstanceStatuses();
    List<String> instanceStatuses = parseCsv(instanceStatusesText);
    String instanceNo = request.getInstanceNo();
    String bizDate = request.getBizDate();
    String traceId = request.getTraceId();
    String startDateText = request.getStartDate();
    ZoneId startDateZone = timezoneProvider.defaultZone();
    Instant startDate = parseFlexibleInstant(startDateText, "startDate", startDateZone);
    String endDateText = request.getEndDate();
    ZoneId endDateZone = timezoneProvider.defaultZone();
    Instant endDate = parseFlexibleInstantEndOfDay(endDateText, "endDate", endDateZone);
    String sortBy = request.getSortBy();
    Integer minDurationSeconds = request.getMinDurationSeconds();
    Boolean slaBreached = request.getSlaBreached();
    JobInstanceQuery query = new JobInstanceQuery(
        tenantId,
        jobCode,
        instanceStatus,
        instanceStatuses,
        instanceNo,
        bizDate,
        traceId,
        startDate,
        endDate,
        sortBy,
        minDurationSeconds,
        slaBreached,
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
    String resolvedTenantId = resolveTenant(tenantGuard, tenantId);
    JobInstanceEntity entity = jobMappers.jobInstanceMapper.selectById(resolvedTenantId, id);
    JobInstanceEntity existingInstance = requireNotNull(entity, "job instance not found");
    return toJobInstanceResponse(existingInstance);
  }

  public List<ConsoleJobInstanceResponse> batchInstanceStatus(
      String tenantId, List<String> instanceNos) {
    String resolved = resolveTenant(tenantGuard, tenantId);
    List<JobInstanceEntity> instances =
        jobMappers.jobInstanceMapper.selectByInstanceNos(resolved, instanceNos);
    return instances.stream().map(this::toJobInstanceResponse).toList();
  }

  public PageResponse<ConsoleJobStepInstanceResponse> jobStepInstances(
      JobStepInstanceQueryRequest request) {
    boolean cursorMode = EmptyChecks.isNotNull(request.getCursor());
    PageRequest pageRequest = cursorMode
        ? new PageRequest(1, request.getPageSize())
        : new PageRequest(request.getPageNo(), request.getPageSize());
    String requestedTenantId = request.getTenantId();
    String tenantId = resolveTenant(tenantGuard, requestedTenantId);
    Long jobInstanceId = request.getJobInstanceId();
    Long jobPartitionId = request.getJobPartitionId();
    String stepCode = request.getStepCode();
    String stepStatus = request.getStepStatus();
    Long cursorId = decodeCursorId(request.getCursor());
    JobStepInstanceQuery query = new JobStepInstanceQuery(
        tenantId, jobInstanceId, jobPartitionId, stepCode, stepStatus, pageRequest, cursorId);
    List<JobStepInstanceEntity> rows = jobMappers.jobStepInstanceMapper.selectByQuery(query);
    if (cursorMode) {
      return cursorPage(
          pageRequest, rows, this::toJobStepInstanceResponse, JobStepInstanceEntity::getId);
    }
    long total = jobMappers.jobStepInstanceMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toJobStepInstanceResponse);
  }

  public ConsoleJobStepInstanceResponse jobStepInstance(String tenantId, Long id) {
    String resolvedTenantId = resolveTenant(tenantGuard, tenantId);
    JobStepInstanceEntity entity =
        jobMappers.jobStepInstanceMapper.selectById(resolvedTenantId, id);
    JobStepInstanceEntity existingStep = requireNotNull(entity, "job step instance not found");
    return toJobStepInstanceResponse(existingStep);
  }

  public PageResponse<ConsoleJobPartitionResponse> jobPartitions(JobPartitionQueryRequest request) {
    boolean cursorMode = EmptyChecks.isNotNull(request.getCursor());
    PageRequest pageRequest = cursorMode
        ? new PageRequest(1, request.getPageSize())
        : new PageRequest(request.getPageNo(), request.getPageSize());
    String requestedTenantId = request.getTenantId();
    String tenantId = resolveTenant(tenantGuard, requestedTenantId);
    Long jobInstanceId = request.getJobInstanceId();
    String partitionStatus = request.getPartitionStatus();
    Long cursorId = decodeCursorId(request.getCursor());
    JobPartitionQuery query =
        new JobPartitionQuery(tenantId, jobInstanceId, partitionStatus, pageRequest, cursorId);
    List<JobPartitionEntity> rows = jobMappers.jobPartitionMapper.selectByQuery(query);
    if (cursorMode) {
      return cursorPage(pageRequest, rows, this::toJobPartitionResponse, JobPartitionEntity::getId);
    }
    long total = jobMappers.jobPartitionMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toJobPartitionResponse);
  }

  private ConsoleJobPartitionResponse toJobPartitionResponse(JobPartitionEntity entity) {
    Long id = entity.getId();
    String tenantId = display(entity.getTenantId());
    Long jobInstanceId = entity.getJobInstanceId();
    Integer partitionNo = entity.getPartitionNo();
    String partitionKey = display(entity.getPartitionKey());
    String partitionStatus = display(entity.getPartitionStatus());
    String workerGroup = display(entity.getWorkerGroup());
    String workerCode = display(entity.getWorkerCode());
    Integer retryCount = entity.getRetryCount();
    String businessKey = display(entity.getBusinessKey());
    Instant leaseExpireAt = entity.getLeaseExpireAt();
    Instant startedAt = entity.getStartedAt();
    Instant finishedAt = entity.getFinishedAt();
    String errorCode = display(entity.getErrorCode());
    String errorMessage = display(entity.getErrorMessage());
    return ConsoleJobPartitionResponse.builder()
        .id(id)
        .tenantId(tenantId)
        .jobInstanceId(jobInstanceId)
        .partitionNo(partitionNo)
        .partitionKey(partitionKey)
        .partitionStatus(partitionStatus)
        .workerGroup(workerGroup)
        .workerCode(workerCode)
        .retryCount(retryCount)
        .businessKey(businessKey)
        .leaseExpireAt(leaseExpireAt)
        .startedAt(startedAt)
        .finishedAt(finishedAt)
        .errorCode(errorCode)
        .errorMessage(errorMessage)
        .build();
  }

  private ConsoleJobDefinitionResponse toJobDefinitionResponse(JobDefinitionEntity entity) {
    String maskedDefaultParams = SecretMasking.maskSensitiveJson(entity.getDefaultParams());
    ConsoleJobDefinitionResponse response = ConsoleJobDefinitionResponse.builder()
        .id(entity.getId())
        .tenantId(display(entity.getTenantId()))
        .jobCode(display(entity.getJobCode()))
        .dependsOnJobCode(display(entity.getDependsOnJobCode()))
        .dependsOnJobCodes(
            EmptyChecks.isNull(entity.getDependsOnJobCodes())
                ? List.of()
                : entity.getDependsOnJobCodes())
        .jobDependencyRules(
            EmptyChecks.isNull(entity.getJobDependencyRules())
                ? List.of()
                : entity.getJobDependencyRules())
        .jobName(display(entity.getJobName()))
        .jobType(display(entity.getJobType()))
        .bizType(display(entity.getBizType()))
        .queueCode(display(entity.getQueueCode()))
        .workerGroup(display(entity.getWorkerGroup()))
        .scheduleType(display(entity.getScheduleType()))
        .scheduleExpr(display(entity.getScheduleExpr()))
        .timezone(display(entity.getTimezone()))
        .calendarCode(display(entity.getCalendarCode()))
        .windowCode(display(entity.getWindowCode()))
        .triggerMode(display(entity.getTriggerMode()))
        .dagEnabled(entity.getDagEnabled())
        .retryPolicy(display(entity.getRetryPolicy()))
        .retryMaxCount(entity.getRetryMaxCount())
        .timeoutSeconds(entity.getTimeoutSeconds())
        .softRuntimeSeconds(Nullables.coalesce(entity.getSoftRuntimeSeconds(), 0))
        .softRuntimeSeverity(display(entity.getSoftRuntimeSeverity()))
        .startGraceSeconds(Nullables.coalesce(entity.getStartGraceSeconds(), 0))
        .startGraceSeverity(display(entity.getStartGraceSeverity()))
        .completionDeadlineEnabled(entity.getCompletionDeadlineEnabled())
        .completionDeadlineLocalTime(entity.getCompletionDeadlineLocalTime())
        .completionDeadlineDayOffset(entity.getCompletionDeadlineDayOffset())
        .completionDeadlineSeverity(display(entity.getCompletionDeadlineSeverity()))
        .dependencyCompletionWindowSeconds(
            Nullables.coalesce(entity.getDependencyCompletionWindowSeconds(), 0))
        .shardStrategy(display(entity.getShardStrategy()))
        .executionMode(display(entity.getExecutionMode()))
        .watermarkField(display(entity.getWatermarkField()))
        .executionHandler(display(entity.getExecutionHandler()))
        .paramSchema(display(entity.getParamSchema()))
        .defaultParams(maskedDefaultParams)
        .priority(entity.getPriority())
        .version(entity.getVersion())
        .enabled(entity.getEnabled())
        .description(display(entity.getDescription()))
        .createdAt(entity.getCreatedAt())
        .updatedAt(entity.getUpdatedAt())
        .defaultWorkflowDefinitionId(entity.getDefaultWorkflowDefinitionId())
        .build();
    return response;
  }

  private ConsoleJobInstanceResponse toJobInstanceResponse(JobInstanceEntity entity) {
    Long id = entity.getId();
    String tenantId = display(entity.getTenantId());
    String jobCode = display(entity.getJobCode());
    String instanceNo = display(entity.getInstanceNo());
    LocalDate bizDate = entity.getBizDate();
    String triggerType = display(entity.getTriggerType());
    String instanceStatus = display(entity.getInstanceStatus());
    String batchNo = display(entity.getBatchNo());
    String operatorId = display(entity.getOperatorId());
    Boolean rerunFlag = entity.getRerunFlag();
    Boolean retryFlag = entity.getRetryFlag();
    String rerunReason = display(entity.getRerunReason());
    Long relatedFileId = entity.getRelatedFileId();
    Long parentInstanceId = entity.getParentInstanceId();
    String queueCode = display(entity.getQueueCode());
    String workerGroup = display(entity.getWorkerGroup());
    Integer priority = entity.getPriority();
    String traceId = display(entity.getTraceId());
    String rawParamsSnapshot = entity.getParamsSnapshot();
    String paramsSnapshot = SecretMasking.maskSensitiveJson(rawParamsSnapshot);
    String rawResultSummary = entity.getResultSummary();
    String resultSummary = SecretMasking.maskSensitiveJson(rawResultSummary);
    Instant deadlineAt = entity.getDeadlineAt();
    Integer expectedDurationSeconds = entity.getExpectedDurationSeconds();
    Instant slaAlertedAt = entity.getSlaAlertedAt();
    Instant startedAt = entity.getStartedAt();
    Instant finishedAt = entity.getFinishedAt();
    Boolean dryRun = entity.getDryRun();
    String failureClass = display(entity.getFailureClass());
    return ConsoleJobInstanceResponse.builder()
        .id(id)
        .tenantId(tenantId)
        .jobCode(jobCode)
        .instanceNo(instanceNo)
        .bizDate(bizDate)
        .triggerType(triggerType)
        .instanceStatus(instanceStatus)
        .batchNo(batchNo)
        .operatorId(operatorId)
        .rerunFlag(rerunFlag)
        .retryFlag(retryFlag)
        .rerunReason(rerunReason)
        .relatedFileId(relatedFileId)
        .parentInstanceId(parentInstanceId)
        .queueCode(queueCode)
        .workerGroup(workerGroup)
        .priority(priority)
        .traceId(traceId)
        .paramsSnapshot(paramsSnapshot)
        .resultSummary(resultSummary)
        .deadlineAt(deadlineAt)
        .expectedDurationSeconds(expectedDurationSeconds)
        .slaAlertedAt(slaAlertedAt)
        .startedAt(startedAt)
        .finishedAt(finishedAt)
        .dryRun(dryRun)
        .failureClass(failureClass)
        .build();
  }

  private ConsoleJobStepInstanceResponse toJobStepInstanceResponse(JobStepInstanceEntity entity) {
    String errorMessage = localizedErrorRenderer.render(entity);
    Long id = entity.getId();
    String tenantId = display(entity.getTenantId());
    Long jobInstanceId = entity.getJobInstanceId();
    Long jobPartitionId = entity.getJobPartitionId();
    Long jobTaskId = entity.getJobTaskId();
    String stepCode = display(entity.getStepCode());
    String stepType = display(entity.getStepType());
    String stepStatus = display(entity.getStepStatus());
    Integer retryCount = entity.getRetryCount();
    Long relatedFileId = entity.getRelatedFileId();
    String rawResultSummary = entity.getResultSummary();
    String resultSummary = SecretMasking.maskSensitiveJson(rawResultSummary);
    String errorCode = display(entity.getErrorCode());
    String displayedErrorMessage = display(errorMessage);
    Long relatedPipelineInstanceId = entity.getRelatedPipelineInstanceId();
    Instant startedAt = entity.getStartedAt();
    Instant finishedAt = entity.getFinishedAt();
    return ConsoleJobStepInstanceResponse.builder()
        .id(id)
        .tenantId(tenantId)
        .jobInstanceId(jobInstanceId)
        .jobPartitionId(jobPartitionId)
        .jobTaskId(jobTaskId)
        .stepCode(stepCode)
        .stepType(stepType)
        .stepStatus(stepStatus)
        .retryCount(retryCount)
        .relatedFileId(relatedFileId)
        .resultSummary(resultSummary)
        .errorCode(errorCode)
        .errorMessage(displayedErrorMessage)
        .relatedPipelineInstanceId(relatedPipelineInstanceId)
        .startedAt(startedAt)
        .finishedAt(finishedAt)
        .build();
  }

  /** Comma-separated -> trimmed list,null/空 → null。供「多状态过滤」入口复用。 */
  private static List<String> parseCsv(String csv) {
    if (csv == null || csv.isBlank()) return null;
    List<String> out = Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();
    return out.isEmpty() ? null : out;
  }
}
