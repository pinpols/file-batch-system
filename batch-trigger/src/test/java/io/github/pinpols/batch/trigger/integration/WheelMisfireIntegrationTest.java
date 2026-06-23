package io.github.pinpols.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.pinpols.batch.common.persistence.entity.TriggerMisfirePendingEntity;
import io.github.pinpols.batch.common.persistence.entity.TriggerRuntimeStateEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.trigger.BatchTriggerApplication;
import io.github.pinpols.batch.trigger.mapper.TriggerMisfirePendingMapper;
import io.github.pinpols.batch.trigger.mapper.TriggerRuntimeStateMapper;
import io.github.pinpols.batch.trigger.wheel.HashedWheelTriggerScheduler;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.assertj.core.data.TemporalUnitOffset;
import org.assertj.core.data.TemporalUnitWithinOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Misfire 三策略 IT — 覆盖 design.md §9 完整方案。
 *
 * <p>测试通过把 next_fire_time 设到 misfireThreshold 之前,模拟"trigger 已经过期未 fire", 然后触发 scanAndSchedule + 等待
 * fire,验证状态机走对。
 *
 * <p>把 misfireThresholdSeconds 调到 2(默认 60),让测试场景容易构造。
 */
@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(
    properties = {
      "batch.trigger.scheduler-impl=wheel",
      "batch.trigger.wheel.sliding-window-scan-interval-seconds=2",
      "batch.trigger.wheel.tick-millis=50",
      "batch.trigger.wheel.misfire-threshold-seconds=2"
    })
@Transactional(propagation = Propagation.NEVER)
class WheelMisfireIntegrationTest extends AbstractIntegrationTest {

  @Autowired private HashedWheelTriggerScheduler wheelScheduler;
  @Autowired private TriggerRuntimeStateMapper stateMapper;
  @Autowired private TriggerMisfirePendingMapper pendingMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  private long jobDefId;
  private String tenantId;
  private String jobCode;

  @BeforeEach
  void seed() {
    // 清 ShedLock 残留(@SchedulerLock lockAtLeastFor 在 IT 之间会保留锁)
    jdbcTemplate.update(
        "delete from batch.shedlock where name in (?, ?)",
        "trigger_wheel_leader",
        "wheel_stale_marker_release");
    tenantId = "wmf-it-" + System.nanoTime();
    jobCode = "job-" + System.nanoTime();
    jdbcTemplate.update(
        "insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE') on"
            + " conflict do nothing",
        tenantId,
        tenantId);
  }

  @Test
  void misfireWithPolicyNoneSkipsAndAdvances() {
    jobDefId = insertJobDefinition("NONE");
    Instant longAgo = BatchDateTimeSupport.utcNow().minusSeconds(30); // 比 threshold(2s) 远超
    insertState(longAgo);

    wheelScheduler.scanAndSchedule(Duration.ofSeconds(60));

    await()
        .atMost(Duration.ofSeconds(8))
        .pollInterval(Duration.ofMillis(200))
        .until(
            () -> {
              TriggerRuntimeStateEntity s = stateMapper.selectByJobDefinitionId(jobDefId);
              return s != null && s.getLastFireStatus() != null;
            });

    TriggerRuntimeStateEntity afterFire = stateMapper.selectByJobDefinitionId(jobDefId);
    assertThat(afterFire.getLastFireStatus()).isEqualTo("MISFIRE_SKIPPED");
    assertThat(afterFire.getMisfireCount()).isEqualTo(1);
    assertThat(afterFire.getNextFireTime()).isAfter(longAgo);
    // NONE 策略不会落 pending 表
    List<TriggerMisfirePendingEntity> pending = pendingMapper.selectPendingByTenant(tenantId, 100);
    assertThat(pending).isEmpty();
  }

  @Test
  void misfireWithPolicyAutoFiresCatchUp() {
    jobDefId = insertJobDefinition("AUTO");
    Instant longAgo = BatchDateTimeSupport.utcNow().minusSeconds(30);
    insertState(longAgo);

    wheelScheduler.scanAndSchedule(Duration.ofSeconds(60));

    await()
        .atMost(Duration.ofSeconds(8))
        .pollInterval(Duration.ofMillis(200))
        .until(
            () -> {
              TriggerRuntimeStateEntity s = stateMapper.selectByJobDefinitionId(jobDefId);
              return s != null && s.getLastFireStatus() != null;
            });

    TriggerRuntimeStateEntity afterFire = stateMapper.selectByJobDefinitionId(jobDefId);
    // AUTO 走 doFire 路径;orchestrator 不可达 → FAILED;否则 MISFIRE_CATCH_UP / SKIPPED_BY_CALENDAR
    assertThat(afterFire.getLastFireStatus())
        .isIn("MISFIRE_CATCH_UP", "FAILED", "SKIPPED_BY_CALENDAR");
    assertThat(afterFire.getNextFireTime()).isAfter(longAgo);
    // AUTO 不落 pending 表
    List<TriggerMisfirePendingEntity> pending = pendingMapper.selectPendingByTenant(tenantId, 100);
    assertThat(pending).isEmpty();
    // trigger_request 由 LaunchService 内部 INSERT(wheel 不再自己写),IT 没起 orchestrator
    // 时 LaunchService 走到 forwardToOrchestrator 才挂,前面的 INSERT 已发生 — 至少 1 行
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.trigger_request where tenant_id = ? and job_code = ?",
            Integer.class,
            tenantId,
            jobCode);
    assertThat(count).isGreaterThanOrEqualTo(1);
  }

  @Test
  void misfireWithPolicyManualApprovalEnqueuesPending() {
    jobDefId = insertJobDefinition("MANUAL_APPROVAL");
    Instant longAgo = BatchDateTimeSupport.utcNow().minusSeconds(30);
    insertState(longAgo);

    wheelScheduler.scanAndSchedule(Duration.ofSeconds(60));

    await()
        .atMost(Duration.ofSeconds(8))
        .pollInterval(Duration.ofMillis(200))
        .until(
            () -> {
              TriggerRuntimeStateEntity s = stateMapper.selectByJobDefinitionId(jobDefId);
              return s != null && "MISFIRE_PENDING".equals(s.getLastFireStatus());
            });

    TriggerRuntimeStateEntity afterFire = stateMapper.selectByJobDefinitionId(jobDefId);
    assertThat(afterFire.getLastFireStatus()).isEqualTo("MISFIRE_PENDING");
    assertThat(afterFire.getMisfireCount()).isEqualTo(1);
    // MANUAL_APPROVAL 落 pending 表,并自动关联待审批 CATCH_UP trigger_request
    List<TriggerMisfirePendingEntity> pending = pendingMapper.selectPendingByTenant(tenantId, 100);
    assertThat(pending).hasSize(1);
    assertThat(pending.get(0).getJobCode()).isEqualTo(jobCode);
    assertThat(pending.get(0).getStatus()).isEqualTo("PENDING");
    assertThat(pending.get(0).getScheduledFireTime()).isCloseTo(longAgo, within1s());
    assertThat(pending.get(0).getCatchUpRequestId()).isNotNull();
    String requestStatus =
        jdbcTemplate.queryForObject(
            "select request_status from batch.trigger_request where id = ?",
            String.class,
            pending.get(0).getCatchUpRequestId());
    assertThat(requestStatus).isEqualTo("ACCEPTED");
  }

  // ── helpers ─────────────────────────────────────────────

  private long insertJobDefinition(String catchUpPolicy) {
    long jdId =
        jdbcTemplate.queryForObject(
            """
            insert into batch.job_definition (
              tenant_id, job_code, job_name, job_type,
              schedule_type, schedule_expr, timezone, calendar_code,
              enabled, created_by, updated_by
            ) values (?, ?, ?, 'GENERAL',
              'CRON', '0 * * * * ?', 'Asia/Shanghai', ?,
              true, 'it', 'it')
            returning id
            """,
            Long.class,
            tenantId,
            jobCode,
            jobCode,
            "cal-" + tenantId);
    // business_calendar 配 catch_up_policy(loader 通过 LEFT JOIN 拿到)
    jdbcTemplate.update(
        """
        insert into batch.business_calendar (
          tenant_id, calendar_code, calendar_name, timezone,
          catch_up_policy, catch_up_max_days, enabled
        ) values (?, ?, ?, 'Asia/Shanghai', ?, 7, true)
        on conflict (tenant_id, calendar_code) do update set catch_up_policy = excluded.catch_up_policy
        """,
        tenantId,
        "cal-" + tenantId,
        "cal-" + tenantId,
        catchUpPolicy);
    return jdId;
  }

  private void insertState(Instant nextFireTime) {
    TriggerRuntimeStateEntity e = new TriggerRuntimeStateEntity();
    e.setJobDefinitionId(jobDefId);
    e.setTenantId(tenantId);
    e.setJobCode(jobCode);
    e.setNextFireTime(nextFireTime);
    stateMapper.insertOnReconcile(e);
  }

  private static TemporalUnitOffset within1s() {
    return new TemporalUnitWithinOffset(1, ChronoUnit.SECONDS);
  }
}
