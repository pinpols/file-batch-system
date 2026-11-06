package io.github.pinpols.batch.trigger.infrastructure;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.trigger.domain.TriggerDefinitionLoader;
import io.github.pinpols.batch.trigger.domain.TriggerStatusInfo;
import io.github.pinpols.batch.trigger.domain.TriggerStatusQueryService;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.stereotype.Service;

/** 只读 Quartz 状态查询门面，与触发器注册、暂停和恢复操作保持独立的调用边界。 */
@Service
@RequiredArgsConstructor
public class TriggerStatusQueryFacade implements TriggerStatusQueryService {

  /** 列表中未进入 Quartz 的 Workflow 调度状态：注销与暂停都会删除 Quartz 任务并关闭调度开关。 */
  static final String UNREGISTERED = "UNREGISTERED";

  private final Scheduler scheduler;
  private final TriggerDefinitionLoader triggerDefinitionLoader;

  @Override
  public List<TriggerStatusInfo> listRegisteredTriggers(String tenantId) {
    try {
      List<TriggerStatusInfo> result = new ArrayList<>();
      for (JobKey jobKey :
          scheduler.getJobKeys(GroupMatcher.jobGroupEquals(TriggerSchedulerFacade.JOB_GROUP))) {
        WorkflowIdentity identity = parseWorkflowIdentity(jobKey);
        if (EmptyChecks.isNull(identity)) {
          continue;
        }
        if (EmptyChecks.isNotNull(tenantId) && !tenantId.equals(identity.tenantId())) {
          continue;
        }
        JobDetail detail = scheduler.getJobDetail(jobKey);
        if (EmptyChecks.isNotNull(detail)) {
          result.add(toTriggerStatusInfo(jobKey, identity, detail));
        }
      }
      appendUnscheduledDefinitions(tenantId, result);
      return result;
    } catch (SchedulerException e) {
      throw new IllegalStateException("failed to list triggers", e);
    }
  }

  /** 补上已配置周期调度但未在 Quartz 中的 Workflow，使控制台可直接重新注册 / 恢复。 */
  private void appendUnscheduledDefinitions(String tenantId, List<TriggerStatusInfo> result) {
    Set<String> scheduled = new HashSet<>();
    for (TriggerStatusInfo info : result) {
      scheduled.add(info.tenantId() + ":" + info.workflowCode());
    }
    for (TriggerDescriptor descriptor :
        triggerDefinitionLoader.loadUnscheduledDefinitions(tenantId)) {
      String identity = descriptor.getTenantId() + ":" + descriptor.getWorkflowCode();
      if (scheduled.add(identity)) {
        result.add(toUnscheduledStatusInfo(descriptor));
      }
    }
  }

  private static TriggerStatusInfo toUnscheduledStatusInfo(TriggerDescriptor descriptor) {
    return TriggerStatusInfo.builder()
        .tenantId(descriptor.getTenantId())
        .jobCode(descriptor.getJobCode())
        .workflowDefinitionId(descriptor.getWorkflowDefinitionId())
        .workflowCode(descriptor.getWorkflowCode())
        .scheduleType(descriptor.getScheduleType())
        .scheduleExpression(descriptor.getScheduleExpression())
        .timezone(descriptor.getTimezone())
        .triggerMode(descriptor.getTriggerMode())
        .status(UNREGISTERED)
        .build();
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
        Set<String> pausedGroups = scheduler.getPausedTriggerGroups();
        return pausedGroups.contains(TriggerSchedulerFacade.JOB_GROUP) ? "PAUSED" : "STARTED";
      }
      return "UNKNOWN";
    } catch (SchedulerException e) {
      throw new IllegalStateException("failed to get scheduler status", e);
    }
  }

  private TriggerStatusInfo toTriggerStatusInfo(
      JobKey jobKey, WorkflowIdentity identity, JobDetail detail) throws SchedulerException {
    JobDataMap data = detail.getJobDataMap();
    TriggerFireState fireState = firstTriggerFireState(jobKey);
    Long workflowDefinitionId = data.containsKey(QuartzLaunchJob.WORKFLOW_DEFINITION_ID)
        ? data.getLongValue(QuartzLaunchJob.WORKFLOW_DEFINITION_ID)
        : null;
    String jobCode = data.getString(QuartzLaunchJob.JOB_CODE);
    return TriggerStatusInfo.builder()
        .tenantId(identity.tenantId())
        .jobCode(jobCode)
        .workflowDefinitionId(workflowDefinitionId)
        .workflowCode(identity.workflowCode())
        .scheduleType(data.getString(QuartzLaunchJob.SCHEDULE_TYPE))
        .scheduleExpression(data.getString(QuartzLaunchJob.SCHEDULE_EXPRESSION))
        .timezone(data.getString(QuartzLaunchJob.TIMEZONE))
        .triggerMode(data.getString(QuartzLaunchJob.TRIGGER_MODE))
        .status(fireState.status())
        .previousFireTime(fireState.previousFireTime())
        .nextFireTime(fireState.nextFireTime())
        .build();
  }

  private static WorkflowIdentity parseWorkflowIdentity(JobKey jobKey) {
    String[] parts = jobKey.getName().split(":", 3);
    boolean partsDoNotDescribeWorkflowIdentity = parts.length != 3
        || EmptyChecks.isBlank(parts[0])
        || !"WF".equals(parts[1])
        || EmptyChecks.isBlank(parts[2]);
    if (partsDoNotDescribeWorkflowIdentity) {
      return null;
    }
    return new WorkflowIdentity(parts[0], parts[2]);
  }

  private TriggerFireState firstTriggerFireState(JobKey jobKey) throws SchedulerException {
    List<? extends Trigger> triggers = scheduler.getTriggersOfJob(jobKey);
    if (EmptyChecks.isEmpty(triggers)) {
      return TriggerFireState.unknown();
    }
    Trigger trigger = triggers.get(0);
    Trigger.TriggerState state = scheduler.getTriggerState(trigger.getKey());
    Instant previousFireTime = toInstant(trigger.getPreviousFireTime());
    Instant nextFireTime = toInstant(trigger.getNextFireTime());
    return new TriggerFireState(state.name(), previousFireTime, nextFireTime);
  }

  private static Instant toInstant(Date fireTime) {
    return EmptyChecks.isNull(fireTime) ? null : fireTime.toInstant();
  }

  private record WorkflowIdentity(String tenantId, String workflowCode) {}

  private record TriggerFireState(String status, Instant previousFireTime, Instant nextFireTime) {

    private static TriggerFireState unknown() {
      return new TriggerFireState("UNKNOWN", null, null);
    }
  }
}
