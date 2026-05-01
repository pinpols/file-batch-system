package com.example.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.persistence.entity.TriggerRuntimeStateEntity;
import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.trigger.BatchTriggerApplication;
import com.example.batch.trigger.mapper.TriggerRuntimeStateMapper;
import com.example.batch.trigger.wheel.HashedWheelTriggerScheduler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import java.time.Duration;
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
 * Failover 行为 IT — 验证 design.md §6 R-4 防御:
 *
 * <p>R-4 风险:Leader 崩溃后,新 leader 冷启动重建窗口 30s+ 期间 fire 全 delay。 防御:wasLeader 翻转检测(@Scheduled 进入时
 * false → true)→ onLeaderAcquire fast-path: 立即接管 stale marker + 立即扫一次 1 min 窗口。
 *
 * <p>测试技巧:wheelScheduler.slidingWindow() 是 public,IT 直接调即可触发 wasLeader 翻转; 配合 stale marker 数据,验证
 * fast-path 接管行为。
 */
@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(
    properties = {
      "batch.trigger.scheduler-impl=wheel",
      "batch.trigger.wheel.stale-marker-threshold-seconds=2" // 2s 阈值,IT 容易构造
    })
@Transactional(propagation = Propagation.NEVER)
class WheelLeaderFailoverIT extends AbstractIntegrationTest {

  @Autowired private HashedWheelTriggerScheduler wheelScheduler;
  @Autowired private TriggerRuntimeStateMapper stateMapper;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private JdbcTemplate jdbcTemplate;

  private long jobDefId;
  private String tenantId;
  private String jobCode;

  @BeforeEach
  void seed() {
    // 清 ShedLock 残留 + 重置 wheelScheduler.wasLeader 状态(每个 test 独立验证 fast-path 翻转)
    jdbcTemplate.update(
        "delete from batch.shedlock where name in (?, ?)",
        "trigger_wheel_leader",
        "wheel_stale_marker_release");
    tenantId = "fover-it-" + System.nanoTime();
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
  void slidingWindowTriggersFastPathOnFirstCallAndReleasesStaleMarkers() {
    // 1) 准备一个 stale marker(模拟"上一任 leader 崩溃前留下的占位")
    insertState(Instant.now().plusSeconds(60));
    TriggerRuntimeStateEntity loaded = stateMapper.selectByJobDefinitionId(jobDefId);
    // 手工 claim + 把 scheduled_at 改到 stale 之前(threshold=2s,设 10s 前)
    stateMapper.claimForSchedule(loaded.getId(), loaded.getVersion(), "dead-leader-instance");
    jdbcTemplate.update(
        "update batch.trigger_runtime_state set scheduled_at = now() - interval '10 seconds' where"
            + " id = ?",
        loaded.getId());

    double leaderAcquireBefore = leaderAcquireCount();
    double staleReleasedBefore = staleReleasedCount();

    // 2) 调 slidingWindow — 触发 wasLeader: false → true → onLeaderAcquire fast-path
    wheelScheduler.doSlidingWindow();

    // 3) 验证 fast-path 行为:
    //    - leader.acquire metric +1
    //    - stale marker 被释放(scheduledFireMarker == null)
    assertThat(leaderAcquireCount()).isGreaterThan(leaderAcquireBefore);
    assertThat(staleReleasedCount()).isGreaterThan(staleReleasedBefore);

    // fast-path 顺序:1) releaseStaleMarkers 把 dead-leader 留下的占位清掉 →
    //                 2) scanAndSchedule(1min) 立即扫一次,本 leader 重新 claim 同一行
    // 所以 marker 不会保持 null,而是变成"本 leader 的 instance id"(不再是 dead-leader)
    TriggerRuntimeStateEntity afterFastPath = stateMapper.selectByJobDefinitionId(jobDefId);
    assertThat(afterFastPath.getScheduledFireMarker())
        .as("dead-leader 的 stale marker 应被释放,然后被本 leader 重新 claim")
        .isNotNull()
        .isNotEqualTo("dead-leader-instance");
  }

  @Test
  void secondSlidingWindowCallDoesNotRetriggerFastPath() {
    insertState(Instant.now().plusSeconds(60));

    wheelScheduler.doSlidingWindow(); // 第 1 次 — 触发 fast-path
    double afterFirstAcquire = leaderAcquireCount();

    wheelScheduler.doSlidingWindow(); // 第 2 次 — wasLeader 已 true,不再触发
    double afterSecondAcquire = leaderAcquireCount();

    assertThat(afterSecondAcquire)
        .as("第二次 slidingWindow 不应再触发 onLeaderAcquire(wasLeader 已 true)")
        .isEqualTo(afterFirstAcquire);
  }

  @Test
  void releaseStaleMarkersAlsoWorksStandalone() {
    // 不依赖 fast-path,独立调 releaseStaleMarkers @Scheduled 入口也能清 stale marker
    insertState(Instant.now().plusSeconds(60));
    TriggerRuntimeStateEntity loaded = stateMapper.selectByJobDefinitionId(jobDefId);
    stateMapper.claimForSchedule(loaded.getId(), loaded.getVersion(), "dead-leader-instance");
    jdbcTemplate.update(
        "update batch.trigger_runtime_state set scheduled_at = now() - interval '10 seconds' where"
            + " id = ?",
        loaded.getId());

    wheelScheduler.releaseStaleMarkers();

    TriggerRuntimeStateEntity afterRelease = stateMapper.selectByJobDefinitionId(jobDefId);
    assertThat(afterRelease.getScheduledFireMarker()).isNull();
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

  private double leaderAcquireCount() {
    return Search.in(meterRegistry).name("batch.trigger.wheel.leader.acquire").counters().stream()
        .mapToDouble(io.micrometer.core.instrument.Counter::count)
        .sum();
  }

  private double staleReleasedCount() {
    return Search.in(meterRegistry)
        .name("batch.trigger.wheel.runtime_state.stale_marker.released")
        .counters()
        .stream()
        .mapToDouble(io.micrometer.core.instrument.Counter::count)
        .sum();
  }

  /** 与 design.md 对应,IT 内复用 — 不需要被外部调用,但保留 for clarity。 */
  Duration shortWindow() {
    return Duration.ofSeconds(60);
  }

  /**
   * QZ-decision-3: failover 循环 100 次,验证无双 fire / 漏 fire / metric 漂移。
   *
   * <p>每轮:dead-leader 占位 → slidingWindow fast-path 接管 → 重置 wasLeader 模拟新一任 leader 加入。 100
   * 轮后断言:每轮都释放了 stale marker(累计 ≥ 100),每轮都 acquire 一次(累计 ≥ 100), marker 始终被本 leader 持有(永不为
   * null,也永不留 dead-leader-instance 残留)。
   */
  @Test
  void failoverLoop100TimesNoDoubleOrMissedFire() {
    insertState(Instant.now().plusSeconds(60));
    TriggerRuntimeStateEntity loaded = stateMapper.selectByJobDefinitionId(jobDefId);

    double leaderAcquireBaseline = leaderAcquireCount();
    double staleReleasedBaseline = staleReleasedCount();

    int rounds = 100;
    int markerNullViolations = 0;
    int deadLeaderResidueViolations = 0;

    for (int i = 0; i < rounds; i++) {
      // 模拟 dead-leader 留下 stale marker
      stateMapper.claimForSchedule(
          loaded.getId(), getCurrentVersion(loaded.getId()), "dead-leader-" + i);
      jdbcTemplate.update(
          "update batch.trigger_runtime_state set scheduled_at = now() - interval '10 seconds'"
              + " where id = ?",
          loaded.getId());

      // 重置 wasLeader 让本次 doSlidingWindow 走 fast-path(模拟新 leader 上任)
      java.util.concurrent.atomic.AtomicBoolean wasLeader =
          (java.util.concurrent.atomic.AtomicBoolean)
              org.springframework.test.util.ReflectionTestUtils.getField(
                  wheelScheduler, "wasLeader");
      if (wasLeader != null) {
        wasLeader.set(false);
      }

      wheelScheduler.doSlidingWindow();

      TriggerRuntimeStateEntity after = stateMapper.selectByJobDefinitionId(jobDefId);
      if (after.getScheduledFireMarker() == null) {
        markerNullViolations++;
      } else if (after.getScheduledFireMarker().startsWith("dead-leader-")) {
        deadLeaderResidueViolations++;
      }
    }

    double leaderAcquireDelta = leaderAcquireCount() - leaderAcquireBaseline;
    double staleReleasedDelta = staleReleasedCount() - staleReleasedBaseline;

    assertThat(markerNullViolations).as("每轮都应被本 leader 重新 claim,不能留 null").isZero();
    assertThat(deadLeaderResidueViolations).as("dead-leader 残留必须每轮释放").isZero();
    assertThat(leaderAcquireDelta)
        .as("100 轮 fast-path 各 acquire 一次")
        .isGreaterThanOrEqualTo(rounds);
    assertThat(staleReleasedDelta).as("100 轮 stale marker 各释放一次").isGreaterThanOrEqualTo(rounds);
  }

  private int getCurrentVersion(Long stateId) {
    return jdbcTemplate.queryForObject(
        "select version from batch.trigger_runtime_state where id = ?", Integer.class, stateId);
  }
}
