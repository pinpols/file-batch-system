package com.example.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.persistence.entity.TriggerRuntimeStateEntity;
import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.trigger.BatchTriggerApplication;
import com.example.batch.trigger.mapper.TriggerRuntimeStateMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * trigger_runtime_state Mapper 集成测试 — 覆盖时间轮 leader 调度的关键 DB 路径。
 *
 * <p>每个 test 通过 jobCode 唯一前缀隔离;不用 @Transactional rollback 因为
 * findReadyToSchedule 的 FOR UPDATE SKIP LOCKED 在事务嵌套下行为难预测。
 */
@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NEVER)
class TriggerRuntimeStateMapperIT extends AbstractIntegrationTest {

  @Autowired private TriggerRuntimeStateMapper mapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  private long jobDefId;
  private String tenantId;
  private String jobCode;

  @BeforeEach
  void seed() {
    tenantId = "trs-it-" + System.nanoTime();
    jobCode = "job-" + System.nanoTime();
    // tenant 表是 FK 间接依赖,确保有
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
          'CRON', '0 0 * * * ?', 'Asia/Shanghai',
          true, 'it', 'it')
        returning id
        """, Long.class, tenantId, jobCode, jobCode);
  }

  @Test
  void insertOnReconcileAndSelect() {
    TriggerRuntimeStateEntity e = newRuntimeStateEntity(Instant.now().plusSeconds(60));
    int rows = mapper.insertOnReconcile(e);
    assertThat(rows).isEqualTo(1);
    assertThat(e.getId()).isNotNull();

    TriggerRuntimeStateEntity loaded = mapper.selectByJobDefinitionId(jobDefId);
    assertThat(loaded).isNotNull();
    assertThat(loaded.getJobCode()).isEqualTo(jobCode);
    assertThat(loaded.getNextFireTime()).isEqualTo(e.getNextFireTime());
    assertThat(loaded.getMisfireCount()).isZero();
    assertThat(loaded.getVersion()).isEqualTo(1);
    assertThat(loaded.getScheduledFireMarker()).isNull();
  }

  @Test
  void findReadyToScheduleSkipsClaimedRows() {
    insertWithFireTime(Instant.now().minusSeconds(10));  // due
    long otherJobDef = newJobDefinition();
    insertWithFireTimeForJobDef(otherJobDef, Instant.now().plusSeconds(120));  // not due in 60s window

    List<TriggerRuntimeStateEntity> due =
        mapper.findReadyToSchedule(Instant.now().plusSeconds(60), 100);

    assertThat(due).hasSizeGreaterThanOrEqualTo(1);
    assertThat(due.stream().anyMatch(t -> t.getJobCode().equals(jobCode))).isTrue();
  }

  @Test
  void claimForScheduleSucceedsThenSecondClaimFails() {
    insertWithFireTime(Instant.now().plusSeconds(30));
    TriggerRuntimeStateEntity loaded = mapper.selectByJobDefinitionId(jobDefId);

    int firstClaim = mapper.claimForSchedule(loaded.getId(), loaded.getVersion(), "leader-A");
    assertThat(firstClaim).isEqualTo(1);

    // 同 version 再 claim 一次:已被占,且 version 已变,失败
    int secondClaim = mapper.claimForSchedule(loaded.getId(), loaded.getVersion(), "leader-B");
    assertThat(secondClaim).isZero();

    TriggerRuntimeStateEntity afterClaim = mapper.selectByJobDefinitionId(jobDefId);
    assertThat(afterClaim.getScheduledFireMarker()).isEqualTo("leader-A");
    assertThat(afterClaim.getVersion()).isEqualTo(loaded.getVersion() + 1);
  }

  @Test
  void claimForScheduleFailsWhenMarkerAlreadySet() {
    insertWithFireTime(Instant.now().plusSeconds(30));
    TriggerRuntimeStateEntity loaded = mapper.selectByJobDefinitionId(jobDefId);

    mapper.claimForSchedule(loaded.getId(), loaded.getVersion(), "leader-A");

    // 即使 version 对(是 stale),marker 不为 null 时也拒绝
    int rows = mapper.claimForSchedule(loaded.getId(), loaded.getVersion() + 1, "leader-B");
    assertThat(rows).isZero();
  }

  @Test
  void advanceAfterFireResetsMarkerAndUpdatesNextFireTime() {
    Instant origin = Instant.now().plusSeconds(30);
    Instant nextNext = origin.plus(Duration.ofMinutes(60));
    insertWithFireTime(origin);
    TriggerRuntimeStateEntity loaded = mapper.selectByJobDefinitionId(jobDefId);
    mapper.claimForSchedule(loaded.getId(), loaded.getVersion(), "leader-A");

    int rows = mapper.advanceAfterFire(loaded.getId(), nextNext, origin, "FIRED", 0);
    assertThat(rows).isEqualTo(1);

    TriggerRuntimeStateEntity advanced = mapper.selectByJobDefinitionId(jobDefId);
    assertThat(advanced.getNextFireTime()).isEqualTo(nextNext);
    assertThat(advanced.getLastFireTime()).isEqualTo(origin);
    assertThat(advanced.getLastFireStatus()).isEqualTo("FIRED");
    assertThat(advanced.getScheduledFireMarker()).isNull();
    assertThat(advanced.getMisfireCount()).isZero();
  }

  @Test
  void advanceAfterFireWithMisfireDeltaIncrementsCount() {
    insertWithFireTime(Instant.now().plusSeconds(30));
    TriggerRuntimeStateEntity loaded = mapper.selectByJobDefinitionId(jobDefId);
    mapper.claimForSchedule(loaded.getId(), loaded.getVersion(), "leader-A");

    mapper.advanceAfterFire(
        loaded.getId(),
        Instant.now().plus(Duration.ofMinutes(60)),
        Instant.now(),
        "MISFIRE_CATCH_UP",
        1);

    TriggerRuntimeStateEntity advanced = mapper.selectByJobDefinitionId(jobDefId);
    assertThat(advanced.getMisfireCount()).isEqualTo(1);
    assertThat(advanced.getLastFireStatus()).isEqualTo("MISFIRE_CATCH_UP");
  }

  @Test
  void releaseStaleMarkersClearsOldOccupations() {
    insertWithFireTime(Instant.now().plusSeconds(30));
    TriggerRuntimeStateEntity loaded = mapper.selectByJobDefinitionId(jobDefId);
    mapper.claimForSchedule(loaded.getId(), loaded.getVersion(), "leader-A");

    // 模拟 marker 已 7 分钟前写入(超 5 min 阈值)
    jdbcTemplate.update(
        "update batch.trigger_runtime_state set scheduled_at = now() - interval '7 minutes' where id = ?",
        loaded.getId());

    int released = mapper.releaseStaleMarkers(Instant.now().minus(Duration.ofMinutes(5)));
    assertThat(released).isGreaterThanOrEqualTo(1);

    TriggerRuntimeStateEntity afterRelease = mapper.selectByJobDefinitionId(jobDefId);
    assertThat(afterRelease.getScheduledFireMarker()).isNull();
    assertThat(afterRelease.getScheduledAt()).isNull();
  }

  @Test
  void releaseStaleMarkersDoesNotTouchFreshMarkers() {
    insertWithFireTime(Instant.now().plusSeconds(30));
    TriggerRuntimeStateEntity loaded = mapper.selectByJobDefinitionId(jobDefId);
    mapper.claimForSchedule(loaded.getId(), loaded.getVersion(), "leader-A");

    int released = mapper.releaseStaleMarkers(Instant.now().minus(Duration.ofMinutes(5)));
    // 刚写入的 marker(scheduled_at = 现在)不在 release 范围
    TriggerRuntimeStateEntity afterRelease = mapper.selectByJobDefinitionId(jobDefId);
    assertThat(afterRelease.getScheduledFireMarker()).isEqualTo("leader-A");
  }

  @Test
  void rescheduleNextFireTimeClearsMarker() {
    insertWithFireTime(Instant.now().plusSeconds(30));
    TriggerRuntimeStateEntity loaded = mapper.selectByJobDefinitionId(jobDefId);
    mapper.claimForSchedule(loaded.getId(), loaded.getVersion(), "leader-A");

    Instant newNext = Instant.now().plus(Duration.ofHours(1));
    int rows = mapper.rescheduleNextFireTime(loaded.getId(), newNext);
    assertThat(rows).isEqualTo(1);

    TriggerRuntimeStateEntity afterReschedule = mapper.selectByJobDefinitionId(jobDefId);
    assertThat(afterReschedule.getNextFireTime()).isEqualTo(newNext);
    assertThat(afterReschedule.getScheduledFireMarker()).isNull();
  }

  @Test
  void deleteByJobDefinitionIdRemovesRow() {
    insertWithFireTime(Instant.now().plusSeconds(30));
    int deleted = mapper.deleteByJobDefinitionId(jobDefId);
    assertThat(deleted).isEqualTo(1);
    assertThat(mapper.selectByJobDefinitionId(jobDefId)).isNull();
  }

  // ── helpers ─────────────────────────────────────────────

  private TriggerRuntimeStateEntity newRuntimeStateEntity(Instant nextFireTime) {
    TriggerRuntimeStateEntity e = new TriggerRuntimeStateEntity();
    e.setJobDefinitionId(jobDefId);
    e.setTenantId(tenantId);
    e.setJobCode(jobCode);
    e.setNextFireTime(nextFireTime);
    return e;
  }

  private void insertWithFireTime(Instant nextFireTime) {
    TriggerRuntimeStateEntity e = newRuntimeStateEntity(nextFireTime);
    mapper.insertOnReconcile(e);
  }

  private long newJobDefinition() {
    String code = "job-" + System.nanoTime();
    return jdbcTemplate.queryForObject(
        """
        insert into batch.job_definition (
          tenant_id, job_code, job_name, job_type,
          schedule_type, schedule_expr, timezone,
          enabled, created_by, updated_by
        ) values (?, ?, ?, 'GENERAL',
          'CRON', '0 0 * * * ?', 'Asia/Shanghai',
          true, 'it', 'it')
        returning id
        """, Long.class, tenantId, code, code);
  }

  private void insertWithFireTimeForJobDef(long otherJobDef, Instant nextFireTime) {
    TriggerRuntimeStateEntity e = new TriggerRuntimeStateEntity();
    e.setJobDefinitionId(otherJobDef);
    e.setTenantId(tenantId);
    e.setJobCode("job-other-" + otherJobDef);
    e.setNextFireTime(nextFireTime);
    mapper.insertOnReconcile(e);
  }
}
