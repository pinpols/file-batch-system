package com.example.batch.orchestrator.infrastructure.sensor;

import com.example.batch.common.rls.RlsTenantContextHolder;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.application.service.sensor.SensorStateMachine;
import com.example.batch.orchestrator.config.SensorProperties;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.infrastructure.tenant.ActiveTenantProvider;
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
 *
 * <p>Citus 路由：{@code selectDueWaitNodes} 以 tenant_id 等值作为首条件，使 FOR UPDATE SKIP LOCKED
 * 合法化（跨分片禁止；单分片合法）。每 tick 按租户顺序循环，单租户异常隔离不影响后续租户。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "batch.sensor.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class SensorPollScheduler {

  /** 每租户单 tick 最多处理节点数（Citus 租户路由后语义：每次 fetchDue 已限定在单个租户分片内， 此上限防单租户大量积压时压垮 DB，并非全局总量上限）。 */
  private static final int MAX_PER_TICK = 50;

  private final WorkflowNodeRunMapper nodeRunMapper;
  private final SensorStateMachine stateMachine;
  private final SensorProperties props;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final ActiveTenantProvider activeTenantProvider;

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
    List<String> tenantIds = activeTenantProvider.activeTenantIds();
    for (String tenantId : tenantIds) {
      try {
        scanTenant(tenantId, now);
      } catch (Exception e) {
        log.warn("Sensor scan failed for tenant, skipping: tenantId={}", tenantId, e);
      }
    }
  }

  /** 单租户扫描：fetchDue（单分片 FOR UPDATE SKIP LOCKED）→ 逐节点 probeOne。 */
  private void scanTenant(String tenantId, Instant now) {
    List<WorkflowNodeRunEntity> due = fetchDue(tenantId, now);
    if (due.isEmpty()) {
      return;
    }
    log.debug(
        "Sensor scheduler tick: tenantId={} {} due WAIT nodes (interval={})",
        tenantId,
        due.size(),
        props.getScanInterval());
    for (WorkflowNodeRunEntity nodeRun : due) {
      try {
        RlsTenantContextHolder.runWithTenant(tenantId, () -> probeOne(nodeRun, now));
      } catch (Exception e) {
        // R2-P2-6：带 stack 让运维能直接定位根因。
        log.warn(
            "Sensor probe tick failed tenantId={} nodeRunId={} err={}",
            tenantId,
            nodeRun.getId(),
            e.getMessage(),
            e);
      }
    }
  }

  /**
   * REQUIRES_NEW: FOR UPDATE SKIP LOCKED 行锁在事务结束才释放；单租户独立事务保证锁粒度最小。
   *
   * <p>tenantId 首参使 Citus 路由到单分片，FOR UPDATE 在单分片内合法。
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<WorkflowNodeRunEntity> fetchDue(String tenantId, Instant now) {
    return nodeRunMapper.selectDueWaitNodes(tenantId, now, MAX_PER_TICK);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void probeOne(WorkflowNodeRunEntity nodeRun, Instant now) {
    stateMachine.probeAndAdvance(nodeRun, now);
  }
}
