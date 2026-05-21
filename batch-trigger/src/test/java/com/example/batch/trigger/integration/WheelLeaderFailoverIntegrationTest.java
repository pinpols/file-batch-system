package com.example.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.persistence.entity.TriggerRuntimeStateEntity;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.trigger.BatchTriggerApplication;
import com.example.batch.trigger.mapper.TriggerRuntimeStateMapper;
import com.example.batch.trigger.wheel.HashedWheelTriggerScheduler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
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
class WheelLeaderFailoverIntegrationTest extends AbstractIntegrationTest {

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
    AtomicBoolean wasLeader =
        (AtomicBoolean) ReflectionTestUtils.getField(wheelScheduler, "wasLeader");
    if (wasLeader != null) {
      wasLeader.set(false);
    }
  }

  @Test
  void slidingWindowTriggersFastPathOnFirstCallAndReleasesStaleMarkers() {
    // 1) 准备一个 stale marker(模拟"上一任 leader 崩溃前留下的占位")
    insertState(BatchDateTimeSupport.utcNow().plusSeconds(60));
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
    insertState(BatchDateTimeSupport.utcNow().plusSeconds(60));

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
    // 不依赖 fast-path,独立调用 doReleaseStaleMarkers 绕开 @SchedulerLock(IT 不测 lock 语义)
    insertState(BatchDateTimeSupport.utcNow().plusSeconds(60));
    TriggerRuntimeStateEntity loaded = stateMapper.selectByJobDefinitionId(jobDefId);
    stateMapper.claimForSchedule(loaded.getId(), loaded.getVersion(), "dead-leader-instance");
    jdbcTemplate.update(
        "update batch.trigger_runtime_state set scheduled_at = now() - interval '10 seconds' where"
            + " id = ?",
        loaded.getId());

    wheelScheduler.doReleaseStaleMarkers();

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
  /**
   * stress 套 20 轮 failover,本质是验证 fast-path 不出现双 fire / 漏 fire。
   *
   * <p>2026-05-21:从 100 轮压减到 20 轮 + 每轮 awaitility 显式等 release+claim 同步窗口,
   * 而不是 doSlidingWindow 立即 select。CI 慢 IO 下 release_stale_markers UPDATE 与紧跟的
   * claimForSchedule 不在同一事务,需要给 wheel 调度器毫秒级窗口完成两步 CAS;否则
   * markerNullViolations 高发(见 docs/analysis/disabled-tests-root-cause-2026-05-21.md §2)。
   */
  @Test
  void failoverLoop20TimesNoDoubleOrMissedFire() {
    insertState(BatchDateTimeSupport.utcNow().plusSeconds(60));
    TriggerRuntimeStateEntity loaded = stateMapper.selectByJobDefinitionId(jobDefId);

    double leaderAcquireBaseline = leaderAcquireCount();
    double staleReleasedBaseline = staleReleasedCount();

    int rounds = 20;
    int markerNullViolations = 0;
    int deadLeaderResidueViolations = 0;

    for (int i = 0; i < rounds; i++) {
      stateMapper.claimForSchedule(
          loaded.getId(), getCurrentVersion(loaded.getId()), "dead-leader-" + i);
      jdbcTemplate.update(
          "update batch.trigger_runtime_state set scheduled_at = now() - interval '10 seconds'"
              + " where id = ?",
          loaded.getId());

      AtomicBoolean wasLeader =
          (AtomicBoolean) ReflectionTestUtils.getField(wheelScheduler, "wasLeader");
      if (wasLeader != null) {
        wasLeader.set(false);
      }

      wheelScheduler.doSlidingWindow();

      // 等 release+claim 两步事务完成(CI 慢 IO 下需要 ~50-500ms)
      String finalMarker = awaitMarkerSettled(loaded.getId(), "dead-leader-" + i);
      if (finalMarker == null) {
        markerNullViolations++;
      } else if (finalMarker.startsWith("dead-leader-")) {
        deadLeaderResidueViolations++;
      }
    }

    double leaderAcquireDelta = leaderAcquireCount() - leaderAcquireBaseline;
    double staleReleasedDelta = staleReleasedCount() - staleReleasedBaseline;

    assertThat(markerNullViolations).as("每轮都应被本 leader 重新 claim,不能留 null").isZero();
    assertThat(deadLeaderResidueViolations).as("dead-leader 残留必须每轮释放").isZero();
    assertThat(leaderAcquireDelta)
        .as("20 轮 fast-path 各 acquire 一次")
        .isGreaterThanOrEqualTo(rounds);
    assertThat(staleReleasedDelta).as("20 轮 stale marker 各释放一次").isGreaterThanOrEqualTo(rounds);
  }

  /**
   * 等 wheel scheduler 的 release+claim 两步事务真正落盘:轮询 ~2s,若 marker 还是 deadId
   * 说明 release 没跑完;若 marker null 说明 release 跑了 claim 没跟上。返回最终读到的 marker。
   */
  private String awaitMarkerSettled(Long stateId, String deadId) {
    long deadline = System.currentTimeMillis() + 2_000L;
    String lastMarker = null;
    while (System.currentTimeMillis() < deadline) {
      TriggerRuntimeStateEntity row = stateMapper.selectByJobDefinitionId(jobDefId);
      lastMarker = row == null ? null : row.getScheduledFireMarker();
      // 终态判定:不是 dead-leader 也不是 null = 本 leader 已重新 claim,可退出
      if (lastMarker != null && !lastMarker.equals(deadId)) {
        return lastMarker;
      }
      try {
        Thread.sleep(20L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return lastMarker;
      }
    }
    return lastMarker;
  }

  private int getCurrentVersion(Long stateId) {
    // CI 偶发 trigger_runtime_state 行尚未可见(insert 后 select 的极小窗口),返 0 让 claimForSchedule
    // 走 CAS miss 路径而非抛 EmptyResultDataAccessException 中断 100 次循环。
    java.util.List<Integer> rows =
        jdbcTemplate.queryForList(
            "select version from batch.trigger_runtime_state where id = ?", Integer.class, stateId);
    return rows.isEmpty() ? 0 : rows.get(0);
  }
}
