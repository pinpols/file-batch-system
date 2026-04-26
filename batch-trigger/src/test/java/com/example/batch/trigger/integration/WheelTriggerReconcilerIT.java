package com.example.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.persistence.entity.TriggerRuntimeStateEntity;
import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.trigger.BatchTriggerApplication;
import com.example.batch.trigger.mapper.TriggerRuntimeStateMapper;
import com.example.batch.trigger.wheel.WheelTriggerReconciler;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * WheelTriggerReconciler IT — 验证 DB ↔ trigger_runtime_state 同步。
 */
@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {"batch.trigger.scheduler-impl=wheel"})
@Transactional(propagation = Propagation.NEVER)
class WheelTriggerReconcilerIT extends AbstractIntegrationTest {

  @Autowired private WheelTriggerReconciler reconciler;
  @Autowired private TriggerRuntimeStateMapper stateMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  private String tenantId;

  @BeforeEach
  void seed() {
    // 清 ShedLock 残留(IT 之间 @SchedulerLock 的 lockAtLeastFor=PT10S 会保留锁)
    jdbcTemplate.update("delete from batch.shedlock where name = 'wheel_trigger_reconciler'");
    tenantId = "wrec-it-" + System.nanoTime();
    jdbcTemplate.update(
        "insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE') on conflict do nothing",
        tenantId, tenantId);
  }

  @Test
  void enabledTriggerInsertsRuntimeState() {
    long jobDefId = insertJobDefinition("0 0 * * * ?", true);

    reconciler.doReconcile();

    TriggerRuntimeStateEntity state = stateMapper.selectByJobDefinitionId(jobDefId);
    assertThat(state).isNotNull();
    assertThat(state.getTenantId()).isEqualTo(tenantId);
    assertThat(state.getNextFireTime()).isAfter(Instant.now());
    assertThat(state.getMisfireCount()).isZero();
  }

  @Test
  void disabledTriggerDeletesRuntimeState() {
    long jobDefId = insertJobDefinition("0 0 * * * ?", true);
    reconciler.doReconcile();
    assertThat(stateMapper.selectByJobDefinitionId(jobDefId)).isNotNull();

    // disable trigger
    jdbcTemplate.update(
        "update batch.job_definition set enabled = false where id = ?", jobDefId);
    reconciler.doReconcile();

    assertThat(stateMapper.selectByJobDefinitionId(jobDefId)).isNull();
  }

  @Test
  void scheduleExprChangeReschedulesNextFireTime() {
    // 用每小时整点 cron
    long jobDefId = insertJobDefinition("0 0 * * * ?", true);
    reconciler.doReconcile();
    Instant originalNext = stateMapper.selectByJobDefinitionId(jobDefId).getNextFireTime();

    // 改成每分钟整点 — next_fire_time 应当被重算到更近的时刻
    jdbcTemplate.update(
        "update batch.job_definition set schedule_expr = '0 * * * * ?' where id = ?", jobDefId);
    reconciler.doReconcile();

    TriggerRuntimeStateEntity afterChange = stateMapper.selectByJobDefinitionId(jobDefId);
    // 新 next_fire_time 应在原 next 之前(分钟级 vs 小时级)
    assertThat(afterChange.getNextFireTime()).isBeforeOrEqualTo(originalNext);
  }

  @Test
  void reconcileIsIdempotent() {
    long jobDefId = insertJobDefinition("0 0 * * * ?", true);
    reconciler.doReconcile();
    Instant firstNext = stateMapper.selectByJobDefinitionId(jobDefId).getNextFireTime();

    // 二次跑无 schedule 变化 → next_fire_time 不变
    reconciler.doReconcile();
    Instant secondNext = stateMapper.selectByJobDefinitionId(jobDefId).getNextFireTime();
    assertThat(secondNext).isEqualTo(firstNext);
  }

  // ── helpers ─────────────────────────────────────────────

  private long insertJobDefinition(String scheduleExpr, boolean enabled) {
    String jobCode = "job-" + System.nanoTime();
    return jdbcTemplate.queryForObject(
        """
        insert into batch.job_definition (
          tenant_id, job_code, job_name, job_type,
          schedule_type, schedule_expr, timezone,
          enabled, created_by, updated_by
        ) values (?, ?, ?, 'GENERAL',
          'CRON', ?, 'Asia/Shanghai',
          ?, 'it', 'it')
        returning id
        """, Long.class, tenantId, jobCode, jobCode, scheduleExpr, enabled);
  }
}
