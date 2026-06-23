package io.github.pinpols.batch.orchestrator.application.service.workflow;

import io.github.pinpols.batch.orchestrator.application.engine.ScheduleForwarder;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 工作流编排服务的默认实现，作为调度引擎与调度计划之间的薄适配层。
 *
 * <p>该类将 {@link SchedulePlan} 委托给 {@link ScheduleForwarder#advance(SchedulePlan)} 推进， 自身不包含编排逻辑，仅承担
 * Spring Bean 注册和接口适配职责。 编排核心逻辑由 {@code ScheduleForwarder} 负责，确保编排行为可替换而不影响调用方。
 */
@Service
@RequiredArgsConstructor
public class DefaultWorkflowOrchestrationService implements WorkflowOrchestrationService {

  private final ScheduleForwarder scheduleForwarder;

  @Override
  public void submit(SchedulePlan plan) {
    scheduleForwarder.advance(plan);
  }
}
