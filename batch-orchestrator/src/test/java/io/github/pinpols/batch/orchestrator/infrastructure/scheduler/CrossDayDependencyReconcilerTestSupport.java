package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import java.time.Instant;

/** 测试支撑: 供跨包集成测试直接触发单节点对账, 避免反射调用包内可见方法。 */
public final class CrossDayDependencyReconcilerTestSupport {

  private CrossDayDependencyReconcilerTestSupport() {}

  /** 对单个等待节点执行一次对账(与定时扫描中的单条处理一致)。 */
  public static void reconcileOne(
      CrossDayDependencyReconciler reconciler, WorkflowNodeRunEntity nodeRun, Instant now) {
    reconciler.reconcileOne(nodeRun, now);
  }
}
