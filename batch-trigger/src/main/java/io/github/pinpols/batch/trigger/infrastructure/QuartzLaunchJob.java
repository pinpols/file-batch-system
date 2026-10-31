package io.github.pinpols.batch.trigger.infrastructure;

import io.github.pinpols.batch.common.enums.CatchUpPolicyType;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.ScheduledOccurrenceStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.IdGenerator;
import io.github.pinpols.batch.trigger.config.TriggerRuntimeProperties;
import io.github.pinpols.batch.trigger.domain.MisfireHandler;
import io.github.pinpols.batch.trigger.domain.TriggerRegistrationService;
import io.github.pinpols.batch.trigger.domain.command.ScheduledTriggerCommand;
import io.github.pinpols.batch.trigger.infrastructure.readiness.ReadinessWaitHandoverClient;
import io.github.pinpols.batch.trigger.service.ScheduledOccurrenceRecorder;
import io.github.pinpols.batch.trigger.service.TriggerService;
import io.github.pinpols.batch.trigger.service.UpstreamNotReadyException;
import io.github.pinpols.batch.trigger.support.ReadinessWaitWindow;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.stereotype.Component;

/**
 * Quartz Job 执行入口：从 {@link JobDataMap} 重建 {@link TriggerDescriptor}， 根据 drift（{@code actualFireTime
 * - scheduledFireTime}）决定触发类型，再分派给 {@link TriggerService}。
 *
 * <p><b>drift 决策树</b>（按优先级）：
 *
 * <ol>
 *   <li>drift {@code < misfireCatchUpThresholdSeconds} → 正常 {@code SCHEDULED}。
 *   <li>drift ≥ 阈值 + {@code catchUpPolicy=MANUAL_APPROVAL} + 在 maxDays 内 → {@link
 *       TriggerService#createPendingCatchUp}（挂起等待人工审批）。
 *   <li>drift ≥ 阈值 + {@code catchUpPolicy=AUTO} + 在 maxDays 内 → {@link
 *       TriggerService#launchScheduled} 以 {@code CATCH_UP} 类型直接触发。
 *   <li>drift ≥ 阈值 + {@code catchUpPolicy=NONE}，或超过 maxDays → 降级为 {@code SCHEDULED} （不追赶，视作正常触发）。
 * </ol>
 *
 * <p><b>租户暂停自愈</b>：若 {@link TriggerService} 抛出 "tenant is suspended" 异常，自动暂停对应 Workflow 的 Quartz 调度，
 * 防止已停用租户的调度持续触发产生 warn 日志风暴。每个 Quartz 任务必须包含明确的 Workflow ID 与 WorkflowCode；旧 Job 根任务会失败关闭。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuartzLaunchJob implements Job {

  public static final String TENANT_ID = "tenantId";
  public static final String JOB_CODE = "jobCode";
  public static final String WORKFLOW_DEFINITION_ID = "workflowDefinitionId";
  public static final String WORKFLOW_CODE = "workflowCode";
  public static final String WORKFLOW_VERSION = "workflowVersion";
  public static final String JOB_DEFINITION_VERSION = "jobDefinitionVersion";
  public static final String SCHEDULE_TYPE = "scheduleType";
  public static final String SCHEDULE_EXPRESSION = "scheduleExpression";
  public static final String TIMEZONE = "timezone";
  public static final String TRIGGER_MODE = "triggerMode";
  public static final String CALENDAR_CODE = "calendarCode";
  public static final String DEPENDS_ON_JOB_CODE = "dependsOnJobCode";
  public static final String DEPENDS_ON_JOB_CODES = "dependsOnJobCodes";
  public static final String CATCH_UP_POLICY = "catchUpPolicy";
  public static final String CATCH_UP_MAX_DAYS = "catchUpMaxDays";
  public static final String MISFIRE_ORIGINAL_FIRE_TIME = "misfireOriginalFireTime";
  static final String READINESS_ORIGINAL_FIRE_TIME = "readinessOriginalFireTime";
  static final String READINESS_DEFERRED_SINCE = "readinessDeferredSince";
  static final String READINESS_TRIGGER_TYPE = "readinessTriggerType";

  private final TriggerService triggerService;
  private final MisfireHandler misfireHandler;
  private final TriggerRuntimeProperties triggerRuntimeProperties;
  private final TriggerRegistrationService triggerRegistrationService;
  private final MeterRegistry meterRegistry;
  private final ScheduledOccurrenceRecorder occurrenceRecorder;
  private final ReadinessWaitHandoverClient readinessWaitHandoverClient;

  @Override
  public void execute(JobExecutionContext context) throws JobExecutionException {
    JobDataMap jobDataMap = context.getMergedJobDataMap();
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(jobDataMap.getString(TENANT_ID));
    descriptor.setJobCode(jobDataMap.getString(JOB_CODE));
    if (jobDataMap.containsKey(WORKFLOW_DEFINITION_ID)) {
      descriptor.setWorkflowDefinitionId(jobDataMap.getLongValue(WORKFLOW_DEFINITION_ID));
    }
    descriptor.setWorkflowCode(jobDataMap.getString(WORKFLOW_CODE));
    if (jobDataMap.containsKey(WORKFLOW_VERSION)) {
      descriptor.setWorkflowVersion(jobDataMap.getIntValue(WORKFLOW_VERSION));
    }
    descriptor.setJobDefinitionVersion(resolveDefinitionVersion(jobDataMap));
    descriptor.setScheduleType(jobDataMap.getString(SCHEDULE_TYPE));
    descriptor.setScheduleExpression(jobDataMap.getString(SCHEDULE_EXPRESSION));
    descriptor.setTimezone(jobDataMap.getString(TIMEZONE));
    descriptor.setTriggerMode(jobDataMap.getString(TRIGGER_MODE));
    descriptor.setCalendarCode(jobDataMap.getString(CALENDAR_CODE));
    descriptor.setDependsOnJobCode(jobDataMap.getString(DEPENDS_ON_JOB_CODE));
    Object dependencyCodes = jobDataMap.get(DEPENDS_ON_JOB_CODES);
    if (dependencyCodes instanceof List<?> codes) {
      descriptor.setDependsOnJobCodes(codes.stream()
          .filter(String.class::isInstance)
          .map(String.class::cast)
          .toList());
    }
    descriptor.setCatchUpPolicy(jobDataMap.getString(CATCH_UP_POLICY));
    descriptor.setCatchUpMaxDays(resolveCatchUpMaxDays(jobDataMap));
    descriptor.setEnabled(true);
    Long workflowDefinitionId = descriptor.getWorkflowDefinitionId();
    String tenantId = descriptor.getTenantId();
    String workflowCode = descriptor.getWorkflowCode();
    boolean workflowIdentityIsIncomplete = EmptyChecks.isNull(workflowDefinitionId)
        || workflowDefinitionId <= 0
        || EmptyChecks.isBlank(tenantId)
        || EmptyChecks.isBlank(workflowCode);
    if (workflowIdentityIsIncomplete) {
      throw new JobExecutionException(
          "scheduled Quartz task requires an explicit tenant and Workflow identity", false);
    }
    Instant scheduledFireTime = resolveScheduledFireTime(context, jobDataMap);
    Instant actualFireTime = context.getFireTime().toInstant();
    boolean readinessRetry = jobDataMap.containsKey(READINESS_ORIGINAL_FIRE_TIME);
    boolean shouldOpenMisfireApproval =
        !readinessRetry && requiresManualApproval(descriptor, scheduledFireTime, actualFireTime);
    if (shouldOpenMisfireApproval) {
      misfireHandler.handle(TriggerSchedulerFacade.quartzIdentity(descriptor));
      String catchUpBusinessNo = IdGenerator.newBusinessNo("quartz");
      String catchUpTraceId = IdGenerator.newTraceId();
      ScheduledTriggerCommand catchUpCommand = new ScheduledTriggerCommand(
          descriptor, scheduledFireTime, TriggerType.CATCH_UP, catchUpBusinessNo, catchUpTraceId);
      triggerService.createPendingCatchUp(catchUpCommand);
      return;
    }
    TriggerType triggerType = readinessRetry
        ? TriggerType.valueOf(jobDataMap.getString(READINESS_TRIGGER_TYPE))
        : resolveTriggerType(descriptor, scheduledFireTime, actualFireTime);
    try {
      String businessNo = IdGenerator.newBusinessNo("quartz");
      String traceId = IdGenerator.newTraceId();
      ScheduledTriggerCommand scheduledCommand = new ScheduledTriggerCommand(
          descriptor, scheduledFireTime, triggerType, businessNo, traceId);
      triggerService.launchScheduled(scheduledCommand);
      if (readinessRetry) {
        // 正常 fire 成功后取消 Orchestrator 侧接管等待，避免重复恢复启动。
        readinessWaitHandoverClient.close(
            descriptor.getTenantId(), descriptor.getWorkflowDefinitionId(), scheduledFireTime);
      }
    } catch (BizException e) {
      // R-arch-audit-2026-05-23 P1: 用 ResultCode 枚举比较替代 e.getMessage().contains(...) 字符串匹配。
      // i18n 错误信息文本变动不再让此分支静默失效。
      if (e.getCode() == ResultCode.TENANT_SUSPENDED) {
        log.warn(
            "tenant is suspended, auto-pausing workflow schedule: tenantId={}, workflowCode={}",
            descriptor.getTenantId(),
            descriptor.getWorkflowCode());
        triggerRegistrationService.pauseByWorkflowCode(
            descriptor.getTenantId(), descriptor.getWorkflowCode());
      } else {
        throw new JobExecutionException(e, false);
      }
    } catch (UpstreamNotReadyException e) {
      ReadinessFireTiming fireTiming =
          new ReadinessFireTiming(scheduledFireTime, actualFireTime, triggerType);
      handleReadinessWait(context, descriptor, fireTiming, e);
    }
  }

  private void handleReadinessWait(
      JobExecutionContext context,
      TriggerDescriptor descriptor,
      ReadinessFireTiming fireTiming,
      UpstreamNotReadyException cause)
      throws JobExecutionException {
    JobDataMap jobDataMap = context.getMergedJobDataMap();
    Instant proposedSince = jobDataMap.containsKey(READINESS_DEFERRED_SINCE)
        ? Instant.ofEpochMilli(jobDataMap.getLongValue(READINESS_DEFERRED_SINCE))
        : fireTiming.actualFireTime();
    Instant proposedDeadline =
        proposedSince.plusSeconds(triggerRuntimeProperties.getReadinessWindowSeconds());
    if (!occurrenceRecorder.recordWaitingReadiness(
        descriptor,
        fireTiming.originalFireTime(),
        cause.getBizDate(),
        proposedSince,
        proposedDeadline)) {
      return;
    }
    ReadinessWaitWindow window =
        occurrenceRecorder.readinessWaitWindow(descriptor, fireTiming.originalFireTime());
    if (EmptyChecks.isNull(window)) {
      // 在读取前 occurrence 已被其他流程推进，不再移交或创建重试。
      return;
    }
    if (EmptyChecks.isNull(window.deferredSince()) || EmptyChecks.isNull(window.deadline())) {
      throw new JobExecutionException("readiness wait window is incomplete", false);
    }
    readinessWaitHandoverClient.handover(
        descriptor,
        fireTiming.originalFireTime(),
        cause.getBizDate(),
        window.deferredSince(),
        window.deadline());
    scheduleReadinessRetry(context, jobDataMap, descriptor, fireTiming, window, cause);
  }

  private Instant resolveScheduledFireTime(JobExecutionContext context, JobDataMap jobDataMap) {
    if (jobDataMap.containsKey(READINESS_ORIGINAL_FIRE_TIME)) {
      long readinessOriginalFireTimeMillis = jobDataMap.getLongValue(READINESS_ORIGINAL_FIRE_TIME);
      return Instant.ofEpochMilli(readinessOriginalFireTimeMillis);
    }
    if (jobDataMap.containsKey(MISFIRE_ORIGINAL_FIRE_TIME)) {
      long misfireOriginalFireTimeMillis = jobDataMap.getLongValue(MISFIRE_ORIGINAL_FIRE_TIME);
      return Instant.ofEpochMilli(misfireOriginalFireTimeMillis);
    }
    return context.getScheduledFireTime().toInstant();
  }

  /** 重排必须保持同一计划 fire 身份，实际回调时间只用于等待窗口和下一次检查。 */
  private record ReadinessFireTiming(
      Instant originalFireTime, Instant actualFireTime, TriggerType triggerType) {
    private ReadinessFireTiming {
      Objects.requireNonNull(originalFireTime, "originalFireTime");
      Objects.requireNonNull(actualFireTime, "actualFireTime");
      Objects.requireNonNull(triggerType, "triggerType");
    }
  }

  private void scheduleReadinessRetry(
      JobExecutionContext context,
      JobDataMap jobDataMap,
      TriggerDescriptor descriptor,
      ReadinessFireTiming fireTiming,
      ReadinessWaitWindow window,
      UpstreamNotReadyException cause)
      throws JobExecutionException {
    Instant originalFireTime = fireTiming.originalFireTime();
    Instant actualFireTime = fireTiming.actualFireTime();
    TriggerType triggerType = fireTiming.triggerType();
    Instant deferredSince = window.deferredSince();
    Duration waited = Duration.between(deferredSince, actualFireTime);
    if (!actualFireTime.isBefore(window.deadline())) {
      occurrenceRecorder.record(
          descriptor,
          originalFireTime,
          cause.getBizDate(),
          ScheduledOccurrenceStatus.READINESS_TIMEOUT,
          null);
      meterRegistry.counter("batch.trigger.quartz.readiness.timeout").increment();
      log.error(
          "upstream readiness window exceeded, giving up scheduled fire: tenantId={}, jobCode={},"
              + " dependsOn={}, bizDate={}, originalFireTime={}, waitedSeconds={}",
          cause.getTenantId(),
          cause.getJobCode(),
          cause.getDependsOnJobCode(),
          cause.getBizDate(),
          originalFireTime,
          waited.getSeconds());
      return;
    }

    JobDataMap retryData = new JobDataMap(jobDataMap);
    retryData.put(READINESS_ORIGINAL_FIRE_TIME, originalFireTime.toEpochMilli());
    retryData.put(READINESS_DEFERRED_SINCE, deferredSince.toEpochMilli());
    retryData.put(READINESS_TRIGGER_TYPE, triggerType.name());
    Instant retryAt =
        actualFireTime.plusSeconds(triggerRuntimeProperties.getReadinessRecheckIntervalSeconds());
    Trigger retryTrigger = TriggerBuilder.newTrigger()
        .withIdentity("readiness-retry-" + UUID.randomUUID(), TriggerSchedulerFacade.JOB_GROUP)
        .forJob(context.getJobDetail().getKey())
        .usingJobData(retryData)
        .startAt(Date.from(retryAt))
        .withSchedule(
            SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow())
        .build();
    try {
      context.getScheduler().scheduleJob(retryTrigger);
      meterRegistry.counter("batch.trigger.quartz.readiness.deferred").increment();
      log.info(
          "upstream not ready, scheduled Quartz readiness retry: tenantId={}, jobCode={},"
              + " dependsOn={}, bizDate={}, retryAt={}",
          cause.getTenantId(),
          cause.getJobCode(),
          cause.getDependsOnJobCode(),
          cause.getBizDate(),
          retryAt);
    } catch (SchedulerException schedulerException) {
      throw new JobExecutionException(
          "failed to schedule upstream readiness retry", schedulerException);
    }
  }

  private int resolveDefinitionVersion(JobDataMap jobDataMap) {
    Object value = jobDataMap.get(JOB_DEFINITION_VERSION);
    return value instanceof Number number ? number.intValue() : 0;
  }

  private TriggerType resolveTriggerType(
      TriggerDescriptor descriptor, Instant scheduledFireTime, Instant actualFireTime) {
    if (EmptyChecks.isNull(scheduledFireTime) || EmptyChecks.isNull(actualFireTime)) {
      return TriggerType.SCHEDULED;
    }
    long driftSeconds =
        Math.max(0L, actualFireTime.getEpochSecond() - scheduledFireTime.getEpochSecond());
    if (driftSeconds >= triggerRuntimeProperties.getMisfireCatchUpThresholdSeconds()) {
      misfireHandler.handle(TriggerSchedulerFacade.quartzIdentity(descriptor));
      return resolveCatchUpPolicy(descriptor, scheduledFireTime, actualFireTime);
    }
    return TriggerType.SCHEDULED;
  }

  /** Misfire 是否转为 catch-up 由 business_calendar 控制，不再只依赖固定时间阈值。 */
  private TriggerType resolveCatchUpPolicy(
      TriggerDescriptor descriptor, Instant scheduledFireTime, Instant actualFireTime) {
    CatchUpPolicyType catchUpPolicy = CatchUpPolicyType.fromCode(descriptor.getCatchUpPolicy());
    if (catchUpPolicy == CatchUpPolicyType.NONE
        || catchUpPolicy == CatchUpPolicyType.MANUAL_APPROVAL) {
      return TriggerType.SCHEDULED;
    }
    long maxDays =
        EmptyChecks.isNull(descriptor.getCatchUpMaxDays()) ? 0L : descriptor.getCatchUpMaxDays();
    if (maxDays <= 0L) {
      return TriggerType.CATCH_UP;
    }
    long elapsedDays = Duration.between(scheduledFireTime, actualFireTime).toDays();
    long driftDays = Math.max(0L, elapsedDays);
    return driftDays <= maxDays ? TriggerType.CATCH_UP : TriggerType.SCHEDULED;
  }

  private boolean requiresManualApproval(
      TriggerDescriptor descriptor, Instant scheduledFireTime, Instant actualFireTime) {
    if (EmptyChecks.isNull(scheduledFireTime) || EmptyChecks.isNull(actualFireTime)) {
      return false;
    }
    CatchUpPolicyType catchUpPolicy = CatchUpPolicyType.fromCode(descriptor.getCatchUpPolicy());
    if (catchUpPolicy != CatchUpPolicyType.MANUAL_APPROVAL) {
      return false;
    }
    long driftSeconds =
        Math.max(0L, actualFireTime.getEpochSecond() - scheduledFireTime.getEpochSecond());
    if (driftSeconds < triggerRuntimeProperties.getMisfireCatchUpThresholdSeconds()) {
      return false;
    }
    long maxDays =
        EmptyChecks.isNull(descriptor.getCatchUpMaxDays()) ? 0L : descriptor.getCatchUpMaxDays();
    if (maxDays <= 0L) {
      return true;
    }
    long elapsedDays = Duration.between(scheduledFireTime, actualFireTime).toDays();
    long driftDays = Math.max(0L, elapsedDays);
    return driftDays <= maxDays;
  }

  private Integer resolveCatchUpMaxDays(JobDataMap jobDataMap) {
    Object rawValue = jobDataMap.get(CATCH_UP_MAX_DAYS);
    if (rawValue instanceof Integer integer) {
      return integer;
    }
    if (rawValue instanceof Number number) {
      return number.intValue();
    }
    if (rawValue instanceof String string && EmptyChecks.isNotBlank(string)) {
      return Integer.parseInt(string);
    }
    return null;
  }
}
