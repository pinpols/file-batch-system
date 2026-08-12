package io.github.pinpols.batch.orchestrator.infrastructure.sensor;

import io.github.pinpols.batch.orchestrator.application.service.sensor.SensorStateMachine;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sensor 扫描的短事务执行器。
 *
 * <p>到期行查询的 {@code FOR UPDATE SKIP LOCKED} 必须活在真实事务里；单节点探测也必须隔离提交，避免一个坏节点回滚整批。
 */
@Component
@RequiredArgsConstructor
class SensorProbeTransactionExecutor {

  private final WorkflowNodeRunMapper nodeRunMapper;
  private final SensorStateMachine stateMachine;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  List<WorkflowNodeRunEntity> fetchDue(Instant now, int limit) {
    return nodeRunMapper.selectDueWaitNodes(now, limit);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void probeOne(WorkflowNodeRunEntity nodeRun, Instant now) {
    stateMachine.probeAndAdvance(nodeRun, now);
  }
}
