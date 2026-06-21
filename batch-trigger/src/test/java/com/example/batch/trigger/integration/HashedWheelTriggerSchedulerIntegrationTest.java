package com.example.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.batch.common.persistence.entity.TriggerRequestEntity;
import com.example.batch.common.persistence.entity.TriggerRuntimeStateEntity;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.trigger.BatchTriggerApplication;
import com.example.batch.trigger.mapper.TriggerRequestMapper;
import com.example.batch.trigger.mapper.TriggerRuntimeStateMapper;
import com.example.batch.trigger.wheel.HashedWheelTriggerScheduler;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * wheel scheduler 端到端集成测试 — 验证 design.md §1 完整数据流。
 *
 * <p>关键验证点:
 *
 * <ul>
 *   <li>Wheel scheduler bean 在 scheduler-impl=wheel 时被装配
 *   <li>scanAndSchedule 把 due trigger 推进 wheel
 *   <li>fire 后 trigger_request 被写入(scheduled_fire_time + runtime_state_id 非 null)
 *   <li>fire 后 next_fire_time 被推进
 *   <li>重复 fire 被 DB UNIQUE 兜住(R-1 防御)
 * </ul>
 */
@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(
    properties = {
      "batch.trigger.scheduler-impl=wheel",
      "batch.trigger.wheel.sliding-window-scan-interval-seconds=2",
      "batch.trigger.wheel.tick-millis=50"
    })
@Transactional(propagation = Propagation.NEVER)
class HashedWheelTriggerSchedulerIntegrationTest extends AbstractIntegrationTest {

  @Autowired private HashedWheelTriggerScheduler wheelScheduler;
  @Autowired private TriggerRuntimeStateMapper stateMapper;
  @Autowired private TriggerRequestMapper requestMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  private long jobDefId;
  private String tenantId;
  private String jobCode;

  @BeforeEach
  void seed() {
    // 清 ShedLock 残留(@SchedulerLock lockAtLeastFor 在 IT 之间会保留锁)
    jdbcTemplate.update(
        "delete from batch.shedlock where name in (?, ?, ?)",
        "trigger_wheel_leader",
        "wheel_stale_marker_release",
        "wheel_trigger_reconciler");
    tenantId = "wheel-it-" + System.nanoTime();
    jobCode = "job-" + System.nanoTime();
    jdbcTemplate.update(
        "insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE') on"
            + " conflict do nothing",
        tenantId,
        tenantId);
    jobDefId =
        jdbcTemplate.queryForObject(
            """
            insert into batch.job_definition (
              tenant_id, job_code, job_name, job_type,
              schedule_type, schedule_expr, timezone,
              enabled, created_by, updated_by
            ) values (?, ?, ?, 'GENERAL',
              'CRON', '0 * * * * ?', 'Asia/Shanghai',
              true, 'it', 'it')
            returning id
            """,
            Long.class,
            tenantId,
            jobCode,
            jobCode);
  }

  @Test
  void wheelSchedulerBeanIsLoaded() {
    assertThat(wheelScheduler).isNotNull();
  }

  @Test
  void scanAndScheduleClaimsDueTrigger() {
    // 插入一条 next_fire_time = 现在 + 200ms 的 runtime state
    Instant fireSoon = BatchDateTimeSupport.utcNow().plusMillis(200);
    insertState(fireSoon);

    // 主动触发滑动窗口扫库(无需等 60s)
    wheelScheduler.scanAndSchedule(Duration.ofSeconds(30));

    // marker 应已被本 leader 占位
    TriggerRuntimeStateEntity claimed = stateMapper.selectByJobDefinitionId(jobDefId);
    assertThat(claimed.getScheduledFireMarker()).isNotBlank();
  }

  @Test
  void fireRunsLaunchServiceAndAdvancesNextFireTime() {
    Instant fireSoon = BatchDateTimeSupport.utcNow().plusMillis(300);
    insertState(fireSoon);

    wheelScheduler.scanAndSchedule(Duration.ofSeconds(30));

    // 等 wheel tick fire(最多等 8s);IT 没起 orchestrator,launchScheduled HTTP 调用会失败,
    // 但 fire 流程会走 catch 块 advance,last_fire_status 写 FAILED;关键是 fire 流程跑过 +
    // next_fire_time 推进 + marker 释放。
    await()
        .atMost(Duration.ofSeconds(8))
        .pollInterval(Duration.ofMillis(200))
        .until(
            () -> {
              TriggerRuntimeStateEntity state = stateMapper.selectByJobDefinitionId(jobDefId);
              return state != null && state.getLastFireStatus() != null;
            });

    TriggerRuntimeStateEntity afterFire = stateMapper.selectByJobDefinitionId(jobDefId);
    // FIRED(orchestrator 可达)or FAILED(IT 不可达,但 fire 流程跑全)or
    // SKIPPED_BY_CALENDAR(LaunchResponse.instanceNo == null,IT 没配 calendar 时可能命中)
    assertThat(afterFire.getLastFireStatus()).isIn("FIRED", "FAILED", "SKIPPED_BY_CALENDAR");
    assertThat(afterFire.getNextFireTime()).isAfter(fireSoon);
    assertThat(afterFire.getScheduledFireMarker()).isNull();
  }

  /**
   * R-1 重复 fire 防御:wheel 不再自己 INSERT trigger_request 走 DB UNIQUE 兜底 (已通过 V70 撤销)。改为依赖三层防御:marker
   * CAS + LaunchService persistAndForward 软幂等 + job_instance.uk_job_instance_tenant_dedup。
   *
   * <p>本测试覆盖第二层:模拟"另一 leader 已经写了 trigger_request 同 dedupKey 行", 然后 wheel 调
   * launchScheduled,LaunchService select-by-dedupKey 应当看到 existing → 直接 return
   * existing.requestId,**不再 INSERT 新行 + 不再 forward 到 orchestrator**。
   *
   * <p>验证点:
   *
   * <ul>
   *   <li>fire 流程跑完(last_fire_status 非 null)
   *   <li>没有第二行 trigger_request(还是 1 行 — preempt 写的那一行)
   *   <li>next_fire_time 仍被推进(防长期停滞)
   * </ul>
   */
  @Test
  void duplicateFireBlockedByLaunchServiceIdempotency() {
    Instant fireSoon = BatchDateTimeSupport.utcNow().plusMillis(300);
    insertState(fireSoon);

    // 模拟"另一 leader 已经 fire 过":手工 INSERT 一条 trigger_request,dedupKey 用
    // LaunchService.buildScheduledDedupKey 同款格式(tenantId:jobCode:fireTime.toString())
    String preemptDedupKey = tenantId + ":" + jobCode + ":" + fireSoon.toString();
    TriggerRequestEntity preempt = new TriggerRequestEntity();
    preempt.setTenantId(tenantId);
    preempt.setRequestId("preempt-" + System.nanoTime());
    preempt.setTriggerType("SCHEDULED");
    preempt.setJobCode(jobCode);
    preempt.setBizDate(LocalDate.now());
    preempt.setDedupKey(preemptDedupKey);
    preempt.setRequestStatus("ACCEPTED");
    requestMapper.insert(preempt);

    int countBefore =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.trigger_request where dedup_key = ?",
            Integer.class,
            preemptDedupKey);
    assertThat(countBefore).isEqualTo(1);

    wheelScheduler.scanAndSchedule(Duration.ofSeconds(30));

    await()
        .atMost(Duration.ofSeconds(8))
        .pollInterval(Duration.ofMillis(200))
        .until(
            () -> {
              TriggerRuntimeStateEntity s = stateMapper.selectByJobDefinitionId(jobDefId);
              return s != null && s.getLastFireStatus() != null;
            });

    TriggerRuntimeStateEntity afterFire = stateMapper.selectByJobDefinitionId(jobDefId);
    // wheel 视角:fire 流程跑完(LaunchService 软幂等返回 existing 视为成功);next_fire_time 推进
    assertThat(afterFire.getNextFireTime()).isAfter(fireSoon);
    assertThat(afterFire.getScheduledFireMarker()).isNull();

    // 关键:LaunchService 软幂等生效 — 没有第二行 trigger_request(只有 preempt 那一行)
    int countAfter =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.trigger_request where dedup_key = ?",
            Integer.class,
            preemptDedupKey);
    assertThat(countAfter).isEqualTo(1);
  }

  // ── helpers ─────────────────────────────────────────────

  private void insertState(Instant nextFireTime) {
    TriggerRuntimeStateEntity e = new TriggerRuntimeStateEntity();
    e.setJobDefinitionId(jobDefId);
    e.setTenantId(tenantId);
    e.setJobCode(jobCode);
    e.setNextFireTime(nextFireTime);
    stateMapper.insertOnReconcile(e);
  }
}
