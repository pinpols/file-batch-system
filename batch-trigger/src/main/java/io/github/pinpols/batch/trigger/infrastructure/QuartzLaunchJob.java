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
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
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
  public static final String CATCH_UP_POLICY = "catchUpPolicy";
  public static final String CATCH_UP_MAX_DAYS = "catchUpMaxDays";

  private final TriggerService triggerService;
  private final MisfireHandler misfireHandler;
  private final TriggerRuntimeProperties triggerRuntimeProperties;
  private final TriggerRegistrationService triggerRegistrationService;

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
    descriptor.setCatchUpPolicy(jobDataMap.getString(CATCH_UP_POLICY));
    descriptor.setCatchUpMaxDays(resolveCatchUpMaxDays(jobDataMap));
    descriptor.setEnabled(true);
    Instant scheduledFireTime = context.getScheduledFireTime().toInstant();
    Instant actualFireTime = context.getFireTime().toInstant();
    if (requiresManualApproval(descriptor, scheduledFireTime, actualFireTime)) {
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
    TriggerType triggerType = resolveTriggerType(descriptor, scheduledFireTime, actualFireTime);
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
