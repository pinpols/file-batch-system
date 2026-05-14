package com.example.batch.orchestrator.infrastructure.sensor;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.application.service.sensor.SensorStateMachine;
import com.example.batch.orchestrator.config.SensorProperties;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-028 S3：周期扫到期 WAIT 节点 → 调 {@link SensorStateMachine}。
 *
 * <p>单 tick 上限 {@link #MAX_PER_TICK} 防压垮 DB；行锁 {@code SELECT FOR UPDATE SKIP LOCKED} + ShedLock
 * 双层保证多实例 orchestrator 不会重复探同一节点。
 *
 * <p>每节点用独立事务执行（{@link Propagation#REQUIRES_NEW}），避免单个故障节点拖挂整 tick。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "batch.sensor.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class SensorPollScheduler {

  private static final int MAX_PER_TICK = 50;

  private final WorkflowNodeRunMapper nodeRunMapper;
  private final SensorStateMachine stateMachine;
  private final SensorProperties props;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  @Scheduled(fixedDelayString = "${batch.sensor.scan-interval:PT10S}")
  @SchedulerLock(name = "sensor_poll", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
  public void scheduledScan() {
    scan();
  }

  /** 业务入口：scheduled / 手工调用都走此方法；测试时直接调可绕开 ShedLock。 */
  public void scan() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    Instant now = BatchDateTimeSupport.utcNow();
    List<WorkflowNodeRunEntity> due = fetchDue(now);
    if (due.isEmpty()) {
      return;
    }
    log.debug(
        "Sensor scheduler tick: {} due WAIT nodes (interval={})",
        due.size(),
        props.getScanInterval());
    for (WorkflowNodeRunEntity nodeRun : due) {
      try {
        probeOne(nodeRun, now);
      } catch (Exception e) {
        // R2-P2-6：e.toString() 只给类名 + message 不带 stack；sensor policy 内部 NPE 等代码缺陷
        // 会留下数千行无 actionable 信息的 warn。带 stack 让运维能直接定位根因。
        log.warn(
            "Sensor probe tick failed nodeRunId={} err={}", nodeRun.getId(), e.getMessage(), e);
      }
    }
  }

  /** REQUIRES_NEW: 单节点失败不影响后续节点；FOR UPDATE 行锁在事务结束才释放。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<WorkflowNodeRunEntity> fetchDue(Instant now) {
    return nodeRunMapper.selectDueWaitNodes(now, MAX_PER_TICK);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void probeOne(WorkflowNodeRunEntity nodeRun, Instant now) {
    stateMachine.probeAndAdvance(nodeRun, now);
  }
}
