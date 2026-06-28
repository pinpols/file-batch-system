package io.github.pinpols.batch.trigger.infrastructure;

import io.github.pinpols.batch.common.enums.ScheduleType;
import io.github.pinpols.batch.trigger.domain.TriggerDefinitionLoader;
import io.github.pinpols.batch.trigger.domain.TriggerRegistrationService;
import io.github.pinpols.batch.trigger.domain.TriggerStatusInfo;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.quartz.CronExpression;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Quartz 调度器门面，实现 {@link TriggerRegistrationService} 接口，集中管理所有 Quartz job 的 注册、暂停、恢复和状态查询。
 *
 * <p><b>JobKey 格式</b>：{@code tenantId:jobCode}，group 固定为 {@code batch-trigger}； {@link
 * #pauseByTenant}/{@link #resumeByTenant} 通过前缀匹配实现租户级批量操作。
 *
 * <p><b>调度类型支持</b>：
 *
 * <ul>
 *   <li>CRON — 使用 {@code withMisfireHandlingInstructionDoNothing}：Quartz 层不补跑， 错失触发由 {@link
 *       QuartzLaunchJob} 在执行时根据 drift 自行决策 catch-up 策略。
 *   <li>FIXED_RATE — 使用 {@code withMisfireHandlingInstructionNextWithExistingCount}：
 *       只补跑丢失的次数，不累积爆发。
 *   <li>EVENT / MANUAL — 静默跳过，无需 Quartz 注册（由外部事件或人工 API 触发）。
 * </ul>
 *
 * <p><b>幂等注册</b>：{@link #scheduleWithReplace} 在 {@code scheduleJob} 前先 deleteJob， 保证每次调用结果一致，无论 job
 * 是否已存在。{@link #registerAll} 持有 ShedLock ({@code lockAtMostFor=PT15M}) 防止多实例并发重复注册。
 */
// R-arch-audit-2026-05-23 P1: 拆分注入路径 — Quartz 模式装配本类作为 TriggerRegistrationService 实现,
// Wheel 模式装配 NoopTriggerRegistrationService。matchIfMissing=false：application.yml 已为
// scheduler-impl 提供默认值 (wheel)，缺省装配不再回落到 quartz。
@Slf4j
@Service
@ConditionalOnProperty(name = "batch.trigger.scheduler-impl", havingValue = "quartz")
@RequiredArgsConstructor
public class TriggerSchedulerFacade implements TriggerRegistrationService {

  public static final String JOB_GROUP = "batch-trigger";

  private final TriggerDefinitionLoader triggerDefinitionLoader;
  private final Scheduler scheduler;

  @Override
  // M-13: lockAtMostFor 从 PT5M 增加到 PT15M，大集群枚举注册所有触发器可能超过 5 分钟
  @SchedulerLock(name = "trigger_register_all", lockAtMostFor = "PT15M", lockAtLeastFor = "PT10S")
  public void registerAll() {
    List<TriggerDescriptor> descriptors = triggerDefinitionLoader.loadAll();
    descriptors.stream().filter(TriggerDescriptor::isEnabled).forEach(this::scheduleDescriptor);
  }

  @Override
  public void registerByJobCode(String tenantId, String jobCode) {
    TriggerDescriptor descriptor = triggerDefinitionLoader.loadByJobCode(tenantId, jobCode);
    if (descriptor != null && descriptor.isEnabled()) {
      scheduleDescriptor(descriptor);
    }
  }

  @Override
  public void unregisterByJobCode(String tenantId, String jobCode) {
    try {
      JobKey jobKey = JobKey.jobKey(tenantId + ":" + jobCode, JOB_GROUP);
      if (scheduler.checkExists(jobKey)) {
        scheduler.deleteJob(jobKey);
      }
    } catch (SchedulerException e) {
      throw new IllegalStateException("failed to unregister trigger: " + jobCode, e);
    }
  }

  @Override
  public void pauseByJobCode(String tenantId, String jobCode) {
    try {
      JobKey jobKey = JobKey.jobKey(tenantId + ":" + jobCode, JOB_GROUP);
      if (scheduler.checkExists(jobKey)) {
        scheduler.pauseJob(jobKey);
      }
    } catch (SchedulerException e) {
      throw new IllegalStateException("failed to pause trigger: " + jobCode, e);
    }
  }

  @Override
  public void resumeByJobCode(String tenantId, String jobCode) {
    try {
      JobKey jobKey = JobKey.jobKey(tenantId + ":" + jobCode, JOB_GROUP);
      if (scheduler.checkExists(jobKey)) {
        scheduler.resumeJob(jobKey);
      }
    } catch (SchedulerException e) {
      throw new IllegalStateException("failed to resume trigger: " + jobCode, e);
    }
  }

  @Override
  public void pauseByTenant(String tenantId) {
    try {
      String prefix = tenantId + ":";
      for (JobKey jobKey : scheduler.getJobKeys(GroupMatcher.jobGroupEquals(JOB_GROUP))) {
        if (jobKey.getName().startsWith(prefix)) {
          scheduler.pauseJob(jobKey);
        }
      }
    } catch (SchedulerException e) {
      throw new IllegalStateException("failed to pause triggers for tenant: " + tenantId, e);
    }
  }

  @Override
  public void resumeByTenant(String tenantId) {
    try {
      String prefix = tenantId + ":";
      for (JobKey jobKey : scheduler.getJobKeys(GroupMatcher.jobGroupEquals(JOB_GROUP))) {
        if (jobKey.getName().startsWith(prefix)) {
          scheduler.resumeJob(jobKey);
        }
      }
    } catch (SchedulerException e) {
      throw new IllegalStateException("failed to resume triggers for tenant: " + tenantId, e);
    }
  }

  @Override
  public List<TriggerStatusInfo> listRegisteredTriggers() {
    try {
      List<TriggerStatusInfo> result = new ArrayList<>();
      for (JobKey jobKey : scheduler.getJobKeys(GroupMatcher.jobGroupEquals(JOB_GROUP))) {
        JobDetail detail = scheduler.getJobDetail(jobKey);
        if (detail == null) {
          continue;
        }
        JobDataMap data = detail.getJobDataMap();
        String identity = jobKey.getName();
        String[] parts = identity.split(":", 2);
        String tid = parts.length > 0 ? parts[0] : "";
        String jc = parts.length > 1 ? parts[1] : identity;

        List<? extends Trigger> triggers = scheduler.getTriggersOfJob(jobKey);
        String status = "UNKNOWN";
        Instant prevFire = null;
        Instant nextFire = null;
        if (!triggers.isEmpty()) {
          Trigger t = triggers.get(0);
          Trigger.TriggerState state = scheduler.getTriggerState(t.getKey());
          status = state.name();
          if (t.getPreviousFireTime() != null) {
            prevFire = t.getPreviousFireTime().toInstant();
          }
          if (t.getNextFireTime() != null) {
            nextFire = t.getNextFireTime().toInstant();
          }
        }
        TriggerStatusInfo statusInfo =
            TriggerStatusInfo.builder()
                .tenantId(tid)
                .jobCode(jc)
                .scheduleType(data.getString(QuartzLaunchJob.SCHEDULE_TYPE))
                .scheduleExpression(data.getString(QuartzLaunchJob.SCHEDULE_EXPRESSION))
                .timezone(data.getString(QuartzLaunchJob.TIMEZONE))
                .triggerMode(data.getString(QuartzLaunchJob.TRIGGER_MODE))
                .status(status)
                .previousFireTime(prevFire)
                .nextFireTime(nextFire)
                .build();
        result.add(statusInfo);
      }
      return result;
    } catch (SchedulerException e) {
      throw new IllegalStateException("failed to list triggers", e);
    }
  }

  @Override
  public void pauseAll() {
    try {
      scheduler.pauseAll();
    } catch (SchedulerException e) {
      throw new IllegalStateException("failed to pause all triggers", e);
    }
  }

  @Override
  public void resumeAll() {
    try {
      scheduler.resumeAll();
    } catch (SchedulerException e) {
      throw new IllegalStateException("failed to resume all triggers", e);
    }
  }

  @Override
  public String schedulerStatus() {
    try {
      if (scheduler.isShutdown()) {
        return "SHUTDOWN";
      }
      if (scheduler.isInStandbyMode()) {
        return "STANDBY";
      }
      if (scheduler.isStarted()) {
        var pausedGroups = scheduler.getPausedTriggerGroups();
        if (pausedGroups.contains(JOB_GROUP)) {
          return "PAUSED";
        }
        return "STARTED";
      }
      return "UNKNOWN";
    } catch (SchedulerException e) {
      throw new IllegalStateException("failed to get scheduler status", e);
    }
  }

  /**
   * R7-A5: 用 ScheduleType.code() 替代字面量 + Map 路由替代 if-chain（CLAUDE.md §分支消除 + §领域字典）。 EVENT / MANUAL
   * 不在 map 内即静默跳过（无 Quartz 注册）。
   */
  private final Map<String, Consumer<TriggerDescriptor>> scheduleHandlers =
      Map.of(
          ScheduleType.CRON.code(), this::scheduleCronDescriptor,
          ScheduleType.FIXED_RATE.code(), this::scheduleFixedRateDescriptor);

  private void scheduleDescriptor(TriggerDescriptor descriptor) {
    try {
      String scheduleType = descriptor.getScheduleType();
      if (scheduleType != null) {
        var handler = scheduleHandlers.get(scheduleType.toUpperCase(Locale.ROOT));
        if (handler != null) {
          handler.accept(descriptor);
        }
      }
    } catch (IllegalArgumentException e) {
      log.warn(
          "skipping invalid trigger descriptor for job={}/{}: {}",
          descriptor.getTenantId(),
          descriptor.getJobCode(),
          e.getMessage());
    }
  }

  private void scheduleCronDescriptor(TriggerDescriptor descriptor) {
    String expression = descriptor.getScheduleExpression();
    if (expression == null
        || expression.isBlank()
        || !CronExpression.isValidExpression(expression)) {
      throw new IllegalArgumentException(
          "invalid cron expression for job " + descriptor.getJobCode() + ": '" + expression + "'");
    }
    // Linux 5 字段 cron（"分 时 日 月 星期"）在 Quartz 下会被当 6 字段 "秒 分 时 日 月 星期" 解析，
    // 语义严重错位：如 "0 1 * * *" 原意每日 01:00，Quartz 却读成"每小时第 1 分 0 秒触发"。
    // Quartz 标准 cron 必须 6（无年）或 7（有年）字段，此处硬校验，拒绝所有 Linux-cron 误用。
    int fieldCount = expression.trim().split("\\s+").length;
    if (fieldCount < 6 || fieldCount > 7) {
      throw new IllegalArgumentException(
          "cron must be Quartz format (6 or 7 fields: sec min hour day month dow [year]), got "
              + fieldCount
              + " fields for job "
              + descriptor.getJobCode()
              + ": '"
              + expression
              + "'"
              + (fieldCount == 5
                  ? " — Linux 5-field cron is not supported; add a leading '0 ' for seconds"
                  : ""));
    }
    String timezone = descriptor.getTimezone();
    if (timezone != null && !timezone.isBlank()) {
      try {
        ZoneId.of(timezone);
      } catch (DateTimeException e) {
        throw new IllegalArgumentException(
            "invalid timezone for job " + descriptor.getJobCode() + ": '" + timezone + "'", e);
      }
    }
    String identity = descriptor.getTenantId() + ":" + descriptor.getJobCode();
    JobDetail jobDetail = buildJobDetail(descriptor);
    CronTrigger trigger =
        TriggerBuilder.newTrigger()
            .withIdentity(identity, JOB_GROUP)
            .forJob(jobDetail)
            .withSchedule(
                CronScheduleBuilder.cronSchedule(expression)
                    .inTimeZone(TimeZone.getTimeZone(timezone))
                    .withMisfireHandlingInstructionDoNothing())
            .build();
    scheduleWithReplace(jobDetail, trigger);
  }

  private void scheduleFixedRateDescriptor(TriggerDescriptor descriptor) {
    int intervalSeconds = parseFixedRateIntervalSeconds(descriptor);
    String identity = descriptor.getTenantId() + ":" + descriptor.getJobCode();
    JobDetail jobDetail = buildJobDetail(descriptor);
    Trigger trigger =
        TriggerBuilder.newTrigger()
            .withIdentity(identity, JOB_GROUP)
            .forJob(jobDetail)
            .withSchedule(
                SimpleScheduleBuilder.simpleSchedule()
                    .withIntervalInSeconds(intervalSeconds)
                    .repeatForever()
                    .withMisfireHandlingInstructionNextWithExistingCount())
            .build();
    scheduleWithReplace(jobDetail, trigger);
  }

  private JobDetail buildJobDetail(TriggerDescriptor descriptor) {
    String identity = descriptor.getTenantId() + ":" + descriptor.getJobCode();
    JobDataMap jobDataMap = new JobDataMap();
    jobDataMap.put(QuartzLaunchJob.TENANT_ID, descriptor.getTenantId());
    jobDataMap.put(QuartzLaunchJob.JOB_CODE, descriptor.getJobCode());
    jobDataMap.put(QuartzLaunchJob.SCHEDULE_TYPE, descriptor.getScheduleType());
    jobDataMap.put(QuartzLaunchJob.SCHEDULE_EXPRESSION, descriptor.getScheduleExpression());
    jobDataMap.put(QuartzLaunchJob.TIMEZONE, descriptor.getTimezone());
    jobDataMap.put(QuartzLaunchJob.TRIGGER_MODE, descriptor.getTriggerMode());
    jobDataMap.put(QuartzLaunchJob.CALENDAR_CODE, descriptor.getCalendarCode());
    jobDataMap.put(QuartzLaunchJob.DEPENDS_ON_JOB_CODE, descriptor.getDependsOnJobCode());
    jobDataMap.put(QuartzLaunchJob.CATCH_UP_POLICY, descriptor.getCatchUpPolicy());
    jobDataMap.put(QuartzLaunchJob.CATCH_UP_MAX_DAYS, descriptor.getCatchUpMaxDays());
    return JobBuilder.newJob(QuartzLaunchJob.class)
        .withIdentity(identity, JOB_GROUP)
        .usingJobData(jobDataMap)
        .storeDurably()
        .build();
  }

  private void scheduleWithReplace(JobDetail jobDetail, Trigger trigger) {
    try {
      if (scheduler.checkExists(jobDetail.getKey())) {
        scheduler.deleteJob(jobDetail.getKey());
      }
      scheduler.scheduleJob(jobDetail, trigger);
    } catch (SchedulerException e) {
      throw new IllegalStateException(
          "failed to register quartz trigger: " + jobDetail.getKey().getName(), e);
    }
  }

  private int parseFixedRateIntervalSeconds(TriggerDescriptor descriptor) {
    String expression = descriptor.getScheduleExpression();
    if (expression == null || expression.isBlank()) {
      throw new IllegalArgumentException(
          "FIXED_RATE schedule_expr must be a positive integer (seconds) for job: "
              + descriptor.getJobCode());
    }
    int seconds;
    try {
      seconds = Integer.parseInt(expression.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "FIXED_RATE schedule_expr must be a positive integer (seconds) for job: "
              + descriptor.getJobCode()
              + ", got: '"
              + expression
              + "'");
    }
    if (seconds <= 0) {
      throw new IllegalArgumentException(
          "FIXED_RATE interval must be > 0 for job: "
              + descriptor.getJobCode()
              + ", got: "
              + seconds);
    }
    return seconds;
  }
}
