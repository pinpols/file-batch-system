package com.example.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.batch.common.persistence.entity.TriggerRequestEntity;
import com.example.batch.common.persistence.entity.TriggerRuntimeStateEntity;
import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.trigger.BatchTriggerApplication;
import com.example.batch.trigger.mapper.TriggerRequestMapper;
import com.example.batch.trigger.mapper.TriggerRuntimeStateMapper;
import com.example.batch.trigger.wheel.HashedWheelTriggerScheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
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
@TestPropertySource(properties = {
    "batch.trigger.scheduler-impl=wheel",
    "batch.trigger.wheel.sliding-window-scan-interval-seconds=2",
    "batch.trigger.wheel.tick-millis=50"
})
@Transactional(propagation = Propagation.NEVER)
class HashedWheelTriggerSchedulerIT extends AbstractIntegrationTest {

  @Autowired private HashedWheelTriggerScheduler wheelScheduler;
  @Autowired private TriggerRuntimeStateMapper stateMapper;
  @Autowired private TriggerRequestMapper requestMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  private long jobDefId;
  private String tenantId;
  private String jobCode;

  @BeforeEach
  void seed() {
    tenantId = "wheel-it-" + System.nanoTime();
    jobCode = "job-" + System.nanoTime();
    jdbcTemplate.update(
        "insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE') on conflict do nothing",
        tenantId, tenantId);
    jobDefId = jdbcTemplate.queryForObject(
        """
        insert into batch.job_definition (
          tenant_id, job_code, job_name, job_type,
          schedule_type, schedule_expr, timezone,
          enabled, created_by, updated_by
        ) values (?, ?, ?, 'GENERAL',
          'CRON', '0 * * * * ?', 'Asia/Shanghai',
          true, 'it', 'it')
        returning id
        """, Long.class, tenantId, jobCode, jobCode);
  }

  @Test
  void wheelSchedulerBeanIsLoaded() {
    assertThat(wheelScheduler).isNotNull();
  }

  @Test
  void scanAndScheduleClaimsDueTrigger() {
    // 插入一条 next_fire_time = 现在 + 200ms 的 runtime state
    Instant fireSoon = Instant.now().plusMillis(200);
    insertState(fireSoon);

    // 主动触发滑动窗口扫库(无需等 60s)
    wheelScheduler.scanAndSchedule(Duration.ofSeconds(30));

    // marker 应已被本 leader 占位
    TriggerRuntimeStateEntity claimed = stateMapper.selectByJobDefinitionId(jobDefId);
    assertThat(claimed.getScheduledFireMarker()).isNotBlank();
  }

  @Test
  void fireWritesTriggerRequestAndAdvancesNextFireTime() {
    Instant fireSoon = Instant.now().plusMillis(300);
    insertState(fireSoon);

    wheelScheduler.scanAndSchedule(Duration.ofSeconds(30));

    // 等 wheel tick fire(最多等 8s);IT 没起 orchestrator,launchScheduled HTTP 调用会失败,
    // 但 fire 流程会走 catch 块 advance,last_fire_status 写 FAILED;关键是 fire 流程跑过 +
    // trigger_request 写入 + next_fire_time 推进。
    await()
        .atMost(Duration.ofSeconds(8))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> {
          TriggerRuntimeStateEntity state = stateMapper.selectByJobDefinitionId(jobDefId);
          return state != null && state.getLastFireStatus() != null;
        });

    TriggerRuntimeStateEntity afterFire = stateMapper.selectByJobDefinitionId(jobDefId);
    // FIRED(orchestrator 可达)or FAILED(IT 不可达,但 fire 流程仍跑全)
    assertThat(afterFire.getLastFireStatus()).isIn("FIRED", "FAILED");
    assertThat(afterFire.getNextFireTime()).isAfter(fireSoon);
    assertThat(afterFire.getScheduledFireMarker()).isNull();

    // 关键验证:trigger_request 行被写入(在 launchScheduled 之前),新字段填充
    Optional<TriggerRequestEntity> req = findFireRequest(afterFire.getId(), fireSoon);
    assertThat(req).isPresent();
    assertThat(req.get().getTriggerRuntimeStateId()).isEqualTo(afterFire.getId());
    assertThat(req.get().getScheduledFireTime()).isEqualTo(fireSoon);
  }

  @Test
  void duplicateFireBlockedByUniqueConstraint() {
    Instant fireSoon = Instant.now().plusMillis(300);
    insertState(fireSoon);

    // 模拟"另一个 leader 已经 fire 过":手工 INSERT 一条 trigger_request
    TriggerRequestEntity req = new TriggerRequestEntity();
    TriggerRuntimeStateEntity state = stateMapper.selectByJobDefinitionId(jobDefId);
    req.setTenantId(tenantId);
    req.setRequestId("preempt-" + System.nanoTime());
    req.setTriggerType("SCHEDULED");
    req.setJobCode(jobCode);
    req.setBizDate(java.time.LocalDate.now());
    req.setDedupKey("preempt-dedup-" + System.nanoTime());
    req.setRequestStatus("ACCEPTED");
    req.setScheduledFireTime(fireSoon);
    req.setTriggerRuntimeStateId(state.getId());
    requestMapper.insert(req);

    // 现在 wheel scheduler 来 fire — 应被 DB UNIQUE 兜住,状态走 SKIPPED_DUPLICATE
    wheelScheduler.scanAndSchedule(Duration.ofSeconds(30));

    await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> {
          TriggerRuntimeStateEntity s = stateMapper.selectByJobDefinitionId(jobDefId);
          return s != null && s.getLastFireStatus() != null;
        });

    TriggerRuntimeStateEntity afterFire = stateMapper.selectByJobDefinitionId(jobDefId);
    assertThat(afterFire.getLastFireStatus()).isEqualTo("SKIPPED_DUPLICATE");
    assertThat(afterFire.getNextFireTime()).isAfter(fireSoon);
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

  private Optional<TriggerRequestEntity> findFireRequest(long stateId, Instant scheduled) {
    return jdbcTemplate
        .query(
            """
            select * from batch.trigger_request
             where trigger_runtime_state_id = ?
               and scheduled_fire_time = ?
            """,
            (rs, n) -> {
              TriggerRequestEntity e = new TriggerRequestEntity();
              e.setId(rs.getLong("id"));
              e.setTenantId(rs.getString("tenant_id"));
              e.setRequestId(rs.getString("request_id"));
              e.setTriggerType(rs.getString("trigger_type"));
              e.setJobCode(rs.getString("job_code"));
              e.setRequestStatus(rs.getString("request_status"));
              e.setScheduledFireTime(rs.getTimestamp("scheduled_fire_time").toInstant());
              e.setTriggerRuntimeStateId(rs.getLong("trigger_runtime_state_id"));
              return e;
            },
            stateId, java.sql.Timestamp.from(scheduled))
        .stream()
        .findFirst();
  }
}
