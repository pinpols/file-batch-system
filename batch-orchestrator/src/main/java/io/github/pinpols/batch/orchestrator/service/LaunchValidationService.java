package io.github.pinpols.batch.orchestrator.service;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.ReplayExecutionGuard;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.view.JobDefinitionView;
import io.github.pinpols.batch.orchestrator.domain.view.WorkflowDefinitionView;
import java.util.Map;

/**
 * 校验启动请求并加载所需定义，所有操作只读、无需事务。 从 {@link DefaultLaunchService} 中拆分以隔离校验关注点。
 *
 * <p>人工评审记录(2026-05-23):审计曾标记为单实现接口候选删除。 但本接口承载的嵌套 record {@code LaunchLoadResult} 被外部调用方以 {@code
 * LaunchValidationService.LaunchLoadResult} 形式广泛引用 (BatchDayGateService / LaunchBatchDayService /
 * ChildJobLaunchSupport 等), 直接合并接口与实现需要全仓 mass rename, 保留接口待人工评估后再处理。
 */
public interface LaunchValidationService {

  /** 校验请求字段，加载触发请求实体、Job 定义和工作流定义（未找到则置为 REJECTED 并抛异常）， 并检查是否存在重复实例。 */
  LaunchLoadResult load(LaunchRequest request);

  record LaunchLoadResult(
      TriggerRequestEntity triggerRequest,
      JobDefinitionView jobDefinition,
      WorkflowDefinitionView workflowDefinition,
      String workflowCalendarCode,
      JobInstanceEntity existingInstance, // 非空表示重复请求
      WorkflowReplayExecutionBinding workflowReplayExecutionBinding) {

    public LaunchLoadResult(
        TriggerRequestEntity triggerRequest,
        JobDefinitionView jobDefinition,
        WorkflowDefinitionView workflowDefinition,
        String workflowCalendarCode,
        JobInstanceEntity existingInstance) {
      this(
          triggerRequest,
          jobDefinition,
          workflowDefinition,
          workflowCalendarCode,
          existingInstance,
          null);
    }

    public LaunchLoadResult(
        TriggerRequestEntity triggerRequest,
        JobDefinitionView jobDefinition,
        WorkflowDefinitionView workflowDefinition,
        JobInstanceEntity existingInstance) {
      this(
          triggerRequest,
          jobDefinition,
          workflowDefinition,
          EmptyChecks.isNull(workflowDefinition) ? null : workflowDefinition.calendarCode(),
          existingInstance);
    }

    public String effectiveCalendarCode() {
      if (EmptyChecks.isNotNull(workflowCalendarCode)
          && EmptyChecks.isNotBlank(workflowCalendarCode)) {
        return workflowCalendarCode;
      }
      return EmptyChecks.isNull(jobDefinition) ? null : jobDefinition.calendarCode();
    }
  }

  /** 从父 Workflow 执行快照加载、经审批且绑定节点的输入。 */
  record WorkflowReplayExecutionBinding(
      Long replayOperationId,
      Long workflowRunId,
      String nodeCode,
      ReplayExecutionGuard executionGuard,
      Map<String, String> protectedFileTemplateSnapshots,
      Map<String, String> protectedDispatchChannelSnapshots,
      Map<String, Object> dispatchSecretBindings) {

    public WorkflowReplayExecutionBinding {
      protectedFileTemplateSnapshots = EmptyChecks.isNull(protectedFileTemplateSnapshots)
          ? Map.of()
          : Map.copyOf(protectedFileTemplateSnapshots);
      protectedDispatchChannelSnapshots = EmptyChecks.isNull(protectedDispatchChannelSnapshots)
          ? Map.of()
          : Map.copyOf(protectedDispatchChannelSnapshots);
      dispatchSecretBindings = EmptyChecks.isNull(dispatchSecretBindings)
          ? Map.of()
          : Map.copyOf(dispatchSecretBindings);
    }
  }
}
