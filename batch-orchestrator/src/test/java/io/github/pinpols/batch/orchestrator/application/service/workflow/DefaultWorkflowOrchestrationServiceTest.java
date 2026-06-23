package io.github.pinpols.batch.orchestrator.application.service.workflow;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import io.github.pinpols.batch.orchestrator.application.engine.ScheduleForwarder;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlan;
import org.junit.jupiter.api.Test;

/**
 * 守 DefaultWorkflowOrchestrationService 这个薄适配层:submit(plan) 必须无条件转发到 {@link
 * ScheduleForwarder#advance(SchedulePlan)},不允许偷偷加业务逻辑。
 *
 * <p>如果未来此类长出非透传分支,本测试将明确拦截 — 真编排逻辑必须落到 ScheduleForwarder 实现里, 保持调用方解耦。
 */
class DefaultWorkflowOrchestrationServiceTest {

  @Test
  void submitShouldDelegateToScheduleForwarderAdvance() {
    ScheduleForwarder forwarder = mock(ScheduleForwarder.class);
    DefaultWorkflowOrchestrationService service =
        new DefaultWorkflowOrchestrationService(forwarder);
    SchedulePlan plan = mock(SchedulePlan.class);

    service.submit(plan);

    verify(forwarder).advance(plan);
    verifyNoMoreInteractions(forwarder);
  }
}
