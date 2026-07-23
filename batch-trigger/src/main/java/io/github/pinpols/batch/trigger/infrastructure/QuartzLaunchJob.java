package io.github.pinpols.batch.trigger.infrastructure;

import io.github.pinpols.batch.common.enums.CatchUpPolicyType;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.IdGenerator;
import io.github.pinpols.batch.trigger.config.TriggerRuntimeProperties;
import io.github.pinpols.batch.trigger.domain.MisfireHandler;
import io.github.pinpols.batch.trigger.domain.TriggerRegistrationService;
import io.github.pinpols.batch.trigger.domain.command.ScheduledTriggerCommand;
import io.github.pinpols.batch.trigger.service.TriggerService;
import io.github.pinpols.batch.trigger.service.UpstreamNotReadyException;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
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
 * <p><b>租户暂停自愈</b>：若 {@link TriggerService} 抛出"tenant is suspended"异常， 自动调用 {@link
 * TriggerRegistrationService#pauseByJobCode} 暂停 Quartz job， 防止暂停租户的 job 持续触发产生 warn 日志风暴。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuartzLaunchJob implements Job {

  public static final String TENANT_ID = "tenantId";
  public static final String JOB_CODE = "jobCode";
  public static final String SCHEDULE_TYPE = "scheduleType";
  public static final String SCHEDULE_EXPRESSION = "scheduleExpression";
  public static final String TIMEZONE = "timezone";
  public static final String TRIGGER_MODE = "triggerMode";
  public static final String CALENDAR_CODE = "calendarCode";
  public static final String DEPENDS_ON_JOB_CODE = "dependsOnJobCode";
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

  @Override
  public void execute(JobExecutionContext context) throws JobExecutionException {
    JobDataMap jobDataMap = context.getMergedJobDataMap();
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(jobDataMap.getString(TENANT_ID));
    descriptor.setJobCode(jobDataMap.getString(JOB_CODE));
    descriptor.setScheduleType(jobDataMap.getString(SCHEDULE_TYPE));
    descriptor.setScheduleExpression(jobDataMap.getString(SCHEDULE_EXPRESSION));
    descriptor.setTimezone(jobDataMap.getString(TIMEZONE));
    descriptor.setTriggerMode(jobDataMap.getString(TRIGGER_MODE));
    descriptor.setCalendarCode(jobDataMap.getString(CALENDAR_CODE));
    descriptor.setDependsOnJobCode(jobDataMap.getString(DEPENDS_ON_JOB_CODE));
    descriptor.setCatchUpPolicy(jobDataMap.getString(CATCH_UP_POLICY));
    descriptor.setCatchUpMaxDays(resolveCatchUpMaxDays(jobDataMap));
    descriptor.setEnabled(true);
    Instant scheduledFireTime = resolveScheduledFireTime(context, jobDataMap);
    Instant actualFireTime = context.getFireTime().toInstant();
    boolean readinessRetry = jobDataMap.containsKey(READINESS_ORIGINAL_FIRE_TIME);
    if (!readinessRetry && requiresManualApproval(descriptor, scheduledFireTime, actualFireTime)) {
      misfireHandler.handle(descriptor.getTenantId() + ":" + descriptor.getJobCode());
      triggerService.createPendingCatchUp(
          new ScheduledTriggerCommand(
              descriptor,
              scheduledFireTime,
              TriggerType.CATCH_UP,
              IdGenerator.newBusinessNo("quartz"),
              IdGenerator.newTraceId()));
      return;
    }
    TriggerType triggerType =
        readinessRetry
            ? TriggerType.valueOf(jobDataMap.getString(READINESS_TRIGGER_TYPE))
            : resolveTriggerType(descriptor, scheduledFireTime, actualFireTime);
    try {
      triggerService.launchScheduled(
          new ScheduledTriggerCommand(
              descriptor,
              scheduledFireTime,
              triggerType,
              IdGenerator.newBusinessNo("quartz"),
              IdGenerator.newTraceId()));
    } catch (BizException e) {
      // R-arch-audit-2026-05-23 P1: 用 ResultCode 枚举比较替代 e.getMessage().contains(...) 字符串匹配。
      // i18n 错误信息文本变动不再让此分支静默失效。
      if (e.getCode() == ResultCode.TENANT_SUSPENDED) {
        log.warn(
            "tenant is suspended, auto-pausing quartz job: tenantId={}, jobCode={}",
            descriptor.getTenantId(),
            descriptor.getJobCode());
        triggerRegistrationService.pauseByJobCode(
            descriptor.getTenantId(), descriptor.getJobCode());
      } else {
        throw new JobExecutionException(e, false);
      }
    } catch (UpstreamNotReadyException e) {
      scheduleReadinessRetry(
          context, jobDataMap, scheduledFireTime, actualFireTime, triggerType, e);
    }
  }

  private Instant resolveScheduledFireTime(JobExecutionContext context, JobDataMap jobDataMap) {
    if (jobDataMap.containsKey(READINESS_ORIGINAL_FIRE_TIME)) {
      return Instant.ofEpochMilli(jobDataMap.getLongValue(READINESS_ORIGINAL_FIRE_TIME));
    }
    if (jobDataMap.containsKey(MISFIRE_ORIGINAL_FIRE_TIME)) {
      return Instant.ofEpochMilli(jobDataMap.getLongValue(MISFIRE_ORIGINAL_FIRE_TIME));
    }
    return context.getScheduledFireTime().toInstant();
  }

  private void scheduleReadinessRetry(
      JobExecutionContext context,
      JobDataMap jobDataMap,
      Instant originalFireTime,
      Instant actualFireTime,
      TriggerType triggerType,
      UpstreamNotReadyException cause)
      throws JobExecutionException {
    Instant deferredSince =
        jobDataMap.containsKey(READINESS_DEFERRED_SINCE)
            ? Instant.ofEpochMilli(jobDataMap.getLongValue(READINESS_DEFERRED_SINCE))
            : actualFireTime;
    Duration waited = Duration.between(deferredSince, actualFireTime);
    if (waited.getSeconds() >= triggerRuntimeProperties.getReadinessWindowSeconds()) {
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
    org.quartz.Trigger retryTrigger =
        TriggerBuilder.newTrigger()
            .withIdentity("readiness-retry-" + UUID.randomUUID(), TriggerSchedulerFacade.JOB_GROUP)
            .forJob(context.getJobDetail().getKey())
            .usingJobData(retryData)
            .startAt(java.util.Date.from(retryAt))
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

  private TriggerType resolveTriggerType(
      TriggerDescriptor descriptor, Instant scheduledFireTime, Instant actualFireTime) {
    if (scheduledFireTime == null || actualFireTime == null) {
      return TriggerType.SCHEDULED;
    }
    long driftSeconds =
        Math.max(0L, actualFireTime.getEpochSecond() - scheduledFireTime.getEpochSecond());
    if (driftSeconds >= triggerRuntimeProperties.getMisfireCatchUpThresholdSeconds()) {
      misfireHandler.handle(descriptor.getTenantId() + ":" + descriptor.getJobCode());
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
    long maxDays = descriptor.getCatchUpMaxDays() == null ? 0L : descriptor.getCatchUpMaxDays();
    if (maxDays <= 0L) {
      return TriggerType.CATCH_UP;
    }
    long driftDays = Math.max(0L, Duration.between(scheduledFireTime, actualFireTime).toDays());
    return driftDays <= maxDays ? TriggerType.CATCH_UP : TriggerType.SCHEDULED;
  }

  private boolean requiresManualApproval(
      TriggerDescriptor descriptor, Instant scheduledFireTime, Instant actualFireTime) {
    if (scheduledFireTime == null || actualFireTime == null) {
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
    long maxDays = descriptor.getCatchUpMaxDays() == null ? 0L : descriptor.getCatchUpMaxDays();
    if (maxDays <= 0L) {
      return true;
    }
    long driftDays = Math.max(0L, Duration.between(scheduledFireTime, actualFireTime).toDays());
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
    if (rawValue instanceof String string && !string.isBlank()) {
      return Integer.parseInt(string);
    }
    return null;
  }
}
