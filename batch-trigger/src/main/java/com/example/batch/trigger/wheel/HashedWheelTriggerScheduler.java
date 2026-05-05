package com.example.batch.trigger.wheel;

import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.CatchUpPolicyType;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.persistence.entity.TriggerMisfirePendingEntity;
import com.example.batch.common.persistence.entity.TriggerRuntimeStateEntity;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.IdGenerator;
import com.example.batch.trigger.config.WheelSchedulerProperties;
import com.example.batch.trigger.domain.TriggerDefinitionLoader;
import com.example.batch.trigger.domain.command.ScheduledTriggerCommand;
import com.example.batch.trigger.mapper.TriggerMisfirePendingMapper;
import com.example.batch.trigger.mapper.TriggerRuntimeStateMapper;
import com.example.batch.trigger.service.TriggerService;
import com.example.batch.trigger.support.TriggerDescriptor;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.concurrent.DefaultThreadFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException; // 仍用于 misfire pending 写入幂等
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 时间轮 trigger scheduler — 替代 Quartz 的"cron → fire 一次回调"调度引擎。
 *
 * <p><b>启用方式</b>:{@code batch.trigger.scheduler-impl=wheel}(2026-04-26 起为默认值; 显式 {@code
 * BATCH_TRIGGER_SCHEDULER_IMPL=quartz} 切回旧 Quartz 路径)。 详细设计见 {@code
 * docs/architecture/quartz-replacement-design.md}。
 *
 * <p><b>核心数据流</b>(详见 design.md §1):
 *
 * <pre>
 *   @Scheduled slidingWindow (60s tick)
 *      ↓ tryLock ShedLock("trigger_wheel_leader")
 *      ↓ (leader 切换 → onLeaderAcquire fast-path)
 *      ↓ findReadyToSchedule (next_fire_time < now() + 5min, marker IS NULL)
 *      ↓ claimForSchedule (CAS 占位)
 *      ↓ wheel.newTimeout(fire, delay)
 *
 *   wheel tick (100ms 精度)
 *      ↓ fire(state, scheduledFireTime)
 *      ↓ INSERT trigger_request (UNIQUE 约束兜底重复 fire,R-1 防御)
 *      ↓ triggerService.launchScheduled(...)
 *      ↓ advanceAfterFire (next_fire_time += cron.next, 释放 marker)
 * </pre>
 *
 * <p><b>本类作用域</b>:第 2 周交付的最小可用骨架。CRUD 联动 / misfire CATCH_UP / MANUAL_APPROVAL 在第 3 周补充。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "batch.trigger.scheduler-impl", havingValue = "wheel")
@RequiredArgsConstructor
public class HashedWheelTriggerScheduler {

  private final WheelSchedulerProperties props;
  private final TriggerRuntimeStateMapper stateMapper;
  private final TriggerMisfirePendingMapper misfirePendingMapper;
  private final TriggerService triggerService;
  private final TriggerDefinitionLoader definitionLoader;
  private final CronExpressionAdapter cronAdapter;
  private final BatchTimezoneProvider timezoneProvider;
  private final BatchDateTimeSupport dateTimeSupport;
  private final WheelMetrics metrics;
  private final CatchUpThrottle catchUpThrottle;

  private HashedWheelTimer wheel;
  private String leaderInstanceId;

  /** wasLeader 翻转检测(@Scheduled 进入时):false → true 触发 onLeaderAcquire fast-path。 */
  private final AtomicBoolean wasLeader = new AtomicBoolean(false);

  /** 内存 dedup set:防同 leader 周期内重复 push 同一 (runtime_state_id, scheduled_fire_time)。 */
  private final ConcurrentMap<String, Boolean> inFlightFires = new ConcurrentHashMap<>();

  /** Timeout registry:trigger disable / cron 修改时 cancel 用(第 3 周接入 CRUD 联动)。 */
  private final ConcurrentMap<Long, Timeout> timeoutRegistry = new ConcurrentHashMap<>();

  /** 当前 wheel 内 task 估计数(供 metric Gauge 用;不绝对精确,用于趋势观察)。 */
  private final AtomicReference<Long> tasksScheduled = new AtomicReference<>(0L);

  @PostConstruct
  void start() {
    leaderInstanceId =
        (props.getLeaderInstanceId() == null || props.getLeaderInstanceId().isBlank())
            ? defaultLeaderInstanceId()
            : props.getLeaderInstanceId();
    wheel =
        new HashedWheelTimer(
            new DefaultThreadFactory("trigger-wheel"),
            props.getTickMillis(),
            TimeUnit.MILLISECONDS,
            props.getBucketCount());
    metrics.registerTasksScheduledGauge(tasksScheduled::get);
    log.info(
        "HashedWheelTriggerScheduler started: leaderInstanceId={}, tick={}ms, buckets={},"
            + " window={}s, scanIntervalMillis={}",
        leaderInstanceId,
        props.getTickMillis(),
        props.getBucketCount(),
        props.getSlidingWindowSeconds(),
        props.getSlidingWindowScanIntervalMillis());
  }

  @PreDestroy
  void shutdown() {
    log.info("HashedWheelTriggerScheduler shutdown initiated");
    timeoutRegistry.values().forEach(Timeout::cancel);
    timeoutRegistry.clear();
    inFlightFires.clear();
    if (wheel != null) {
      wheel.stop();
    }
    log.info("HashedWheelTriggerScheduler shutdown completed");
  }

  /**
   * 滑动窗口扫库 + 推进 wheel。{@code @SchedulerLock} 保证多实例只有一个 leader 工作。
   *
   * <p>fixedDelay 单位 ms,默认 60_000(60s 一次)。
   */
  @Scheduled(fixedDelayString = "${batch.trigger.wheel.sliding-window-scan-interval-millis:60000}")
  @SchedulerLock(
      name = "${batch.trigger.wheel.leader-lock-name:trigger_wheel_leader}",
      lockAtMostFor = "PT2M",
      lockAtLeastFor = "PT30S")
  public void slidingWindow() {
    doSlidingWindow();
  }

  /**
   * Public 让 IT 直接调,绕开 {@code @SchedulerLock} proxy(IT 不验证 lock 行为)。生产代码不应调用此方法,走 {@link
   * #slidingWindow()}。
   */
  public void doSlidingWindow() {
    boolean previouslyLeader = wasLeader.getAndSet(true);
    if (!previouslyLeader) {
      onLeaderAcquire();
    }
    scanAndSchedule(Duration.ofSeconds(props.getSlidingWindowSeconds()));
  }

  /**
   * stale marker 释放:周期清理超过 staleMarkerThresholdSeconds 未释放的占位。
   *
   * <p>独立定时(默认每 2 min),与 slidingWindow 解耦,避免 leader 漂移期间 stale 占位卡住所有 trigger。
   *
   * <p>2026-04-26 加 ShedLock:虽然 UPDATE 是幂等的,但 N 实例并发跑会撞同批 stale 行的 PG row lock,造成写放大 +
   * 连接浪费。leader-elect 语义本就该有,迟来的 lint 修复。
   */
  @Scheduled(
      fixedDelayString = "${batch.trigger.wheel.stale-marker-release-interval-millis:120000}")
  @SchedulerLock(
      name = "wheel_stale_marker_release",
      lockAtMostFor = "PT3M",
      lockAtLeastFor = "PT30S")
  public void scheduledReleaseStaleMarkers() {
    doReleaseStaleMarkers();
  }

  /**
   * Public 供 IT / 接管路径调用,绕开 {@code @SchedulerLock}(直接调 {@link #scheduledReleaseStaleMarkers} 可能拿不到
   * lock)。
   */
  public void doReleaseStaleMarkers() {
    Instant staleBefore =
        dateTimeSupport
            .nowInstant()
            .minus(Duration.ofSeconds(props.getStaleMarkerThresholdSeconds()));
    int released = stateMapper.releaseStaleMarkers(staleBefore);
    if (released > 0) {
      metrics.incrementStaleMarkerReleased(released);
      log.info(
          "released {} stale trigger_runtime_state markers (older than {}s)",
          released,
          props.getStaleMarkerThresholdSeconds());
    }
  }

  // ── leader 切换 fast-path ───────────────────────────────────

  private void onLeaderAcquire() {
    long startNanos = System.nanoTime();
    metrics.incrementLeaderAcquire();
    log.info("leader acquired (instanceId={}); running fast-path catch-up scan", leaderInstanceId);
    // 1) 清掉本实例 wheel 内可能残留(理论上首次启动应为空)
    timeoutRegistry.values().forEach(Timeout::cancel);
    timeoutRegistry.clear();
    inFlightFires.clear();
    tasksScheduled.set(0L);
    // 2) 接管 stale marker(上一任 leader 崩溃前留下的)
    doReleaseStaleMarkers();
    // 3) 立即扫一次"1 分钟"窗口,先把当下要 fire 的捞出来
    scanAndSchedule(Duration.ofMinutes(1));
    metrics.leaderAcquireDuration().record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
  }

  // ── 滑动窗口扫库 + 推 wheel ─────────────────────────────────

  /** 测试 + 内部都用,public 为了 IT 能直接触发(避免依赖 @Scheduled 60s 周期等)。 */
  public void scanAndSchedule(Duration window) {
    long start = System.nanoTime();
    Instant horizon = dateTimeSupport.nowInstant().plus(window);
    List<TriggerRuntimeStateEntity> due =
        stateMapper.findReadyToSchedule(horizon, props.getScanBatchSize());
    int pushed = 0;
    for (TriggerRuntimeStateEntity state : due) {
      if (claimAndSchedule(state)) {
        pushed++;
      }
    }
    if (pushed > 0) {
      metrics.incrementScanTaskCount(pushed);
    }
    metrics.scanDuration().record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
  }

  private boolean claimAndSchedule(TriggerRuntimeStateEntity state) {
    String dedupKey = state.getId() + ":" + state.getNextFireTime().toEpochMilli();
    if (inFlightFires.putIfAbsent(dedupKey, Boolean.TRUE) != null) {
      return false; // 内存层去重(同 leader 同周期重复扫)
    }
    int claimed = stateMapper.claimForSchedule(state.getId(), state.getVersion(), leaderInstanceId);
    if (claimed == 0) {
      inFlightFires.remove(dedupKey);
      return false; // DB 层去重(其他 leader 已占,或 marker 非空)
    }
    Instant scheduledFireTime = state.getNextFireTime();
    long delayMillis =
        Math.max(0L, scheduledFireTime.toEpochMilli() - dateTimeSupport.currentEpochMillis());
    Timeout timeout =
        wheel.newTimeout(
            _ -> fire(state, scheduledFireTime, dedupKey), delayMillis, TimeUnit.MILLISECONDS);
    timeoutRegistry.put(state.getId(), timeout);
    tasksScheduled.updateAndGet(v -> v + 1);
    return true;
  }

  // ── fire callback ───────────────────────────────────────────

  void fire(TriggerRuntimeStateEntity state, Instant scheduledFireTime, String dedupKey) {
    long actualFireMillis = dateTimeSupport.currentEpochMillis();
    metrics.recordFireLag(actualFireMillis - scheduledFireTime.toEpochMilli());
    String groupTag = "tenant:" + state.getTenantId();
    try {
      TriggerDescriptor descriptor = loadDescriptor(state);
      // 0) misfire 分流:next_fire_time 比 actual 早超过 misfireThreshold 视为 misfire
      long lagSeconds = (actualFireMillis - scheduledFireTime.toEpochMilli()) / 1000L;
      if (lagSeconds >= props.getMisfireThresholdSeconds()) {
        handleMisfire(state, descriptor, scheduledFireTime, groupTag);
        return;
      }
      doFire(state, descriptor, scheduledFireTime, TriggerType.SCHEDULED, groupTag, "FIRED");
    } catch (Exception e) {
      metrics.incrementFireFailed(groupTag, e.getClass().getSimpleName());
      log.warn(
          "trigger fire outer failure: job={} scheduledFireTime={}",
          state.getJobCode(),
          scheduledFireTime,
          e);
      advanceNextFireTime(state, scheduledFireTime, "FAILED", 0);
    } finally {
      timeoutRegistry.remove(state.getId());
      inFlightFires.remove(dedupKey);
      tasksScheduled.updateAndGet(v -> Math.max(0, v - 1));
    }
  }

  /** 按 catchUpPolicy 分流 NONE / AUTO / MANUAL_APPROVAL,详见 design.md §9.2。 */
  private void handleMisfire(
      TriggerRuntimeStateEntity state,
      TriggerDescriptor descriptor,
      Instant scheduledFireTime,
      String groupTag) {
    CatchUpPolicyType policy = CatchUpPolicyType.fromCode(descriptor.getCatchUpPolicy());
    metrics.incrementMisfireHandled(policy.code());
    log.info(
        "misfire detected: job={} scheduledFireTime={} policy={}",
        state.getJobCode(),
        scheduledFireTime,
        policy.code());
    switch (policy) {
      // NONE: 跳过本次 fire,只推进 next_fire_time
      case NONE -> advanceNextFireTime(state, scheduledFireTime, "MISFIRE_SKIPPED", 1);
      case AUTO -> {
        // catch-up throttle:防启动期 / 灰度切换瞬间 100+ 个 misfire 同时打挂 LaunchService
        try {
          catchUpThrottle.acquire();
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          log.warn(
              "catch-up throttle interrupted, skip misfire AUTO fire: job={}", state.getJobCode());
          advanceNextFireTime(state, scheduledFireTime, "MISFIRE_SKIPPED", 1);
          return;
        }
        doFire(
            state,
            descriptor,
            scheduledFireTime,
            TriggerType.CATCH_UP,
            groupTag,
            "MISFIRE_CATCH_UP");
      }
      case MANUAL_APPROVAL -> {
        // 落 trigger_misfire_pending 表,等运维 console 审批
        try {
          TriggerMisfirePendingEntity pending = new TriggerMisfirePendingEntity();
          pending.setTriggerRuntimeStateId(state.getId());
          pending.setTenantId(state.getTenantId());
          pending.setJobCode(state.getJobCode());
          pending.setScheduledFireTime(scheduledFireTime);
          misfirePendingMapper.insertPending(pending);
        } catch (DuplicateKeyException dup) {
          // 已有相同 (state_id, scheduledFireTime) 待审,幂等跳过
          log.info(
              "misfire pending already exists, skip insert: job={} scheduledFireTime={}",
              state.getJobCode(),
              scheduledFireTime);
        }
        advanceNextFireTime(state, scheduledFireTime, "MISFIRE_PENDING", 1);
      }
    }
  }

  /**
   * 实际 fire 主路径(共享 SCHEDULED + CATCH_UP 两种 triggerType)。
   *
   * <p><b>R-1 重复 fire 三层兜底</b>(无需 trigger_request 自加 fire dedup unique):
   *
   * <ol>
   *   <li>marker CAS(claimForSchedule + version):同一 trigger 同 next_fire_time 不可能两个 leader 同时 claim
   *       成功
   *   <li>LaunchService.persistAndForward 内部 select-by-dedupKey 软幂等:GC pause 旧 leader fire
   *       时,LaunchService 看到 existing → return existing.requestId,不再 forward
   *   <li>job_instance 唯一约束 uk_job_instance_tenant_dedup:即使前两层都漏了,业务侧 INSERT job_instance 撞唯一键
   * </ol>
   */
  private void doFire(
      TriggerRuntimeStateEntity state,
      TriggerDescriptor descriptor,
      Instant scheduledFireTime,
      TriggerType triggerType,
      String groupTag,
      String successStatus) {
    String requestId = IdGenerator.newBusinessNo("wheel");
    try {
      ScheduledTriggerCommand command =
          new ScheduledTriggerCommand(
              descriptor, scheduledFireTime, triggerType, requestId, IdGenerator.newTraceId());
      LaunchResponse response = triggerService.launchScheduled(command);
      // LaunchResponse.instanceNo == null = LaunchService 主动跳过(节假日 SKIP rollRule
      // 或 bizDate 为空);不算失败,但需要区分状态
      if (response == null || response.instanceNo() == null) {
        metrics.incrementFireSuccess(groupTag);
        advanceNextFireTime(state, scheduledFireTime, "SKIPPED_BY_CALENDAR", 0);
        return;
      }
      metrics.incrementFireSuccess(groupTag);
      advanceNextFireTime(state, scheduledFireTime, successStatus, 0);
    } catch (Exception e) {
      metrics.incrementFireFailed(groupTag, e.getClass().getSimpleName());
      log.warn(
          "trigger launch failed: job={} scheduledFireTime={}",
          state.getJobCode(),
          scheduledFireTime,
          e);
      advanceNextFireTime(state, scheduledFireTime, "FAILED", 0);
    }
  }

  private void advanceNextFireTime(
      TriggerRuntimeStateEntity state,
      Instant scheduledFireTime,
      String lastFireStatus,
      long misfireDelta) {
    try {
      TriggerDescriptor descriptor = loadDescriptor(state);
      ZoneId zoneId = timezoneProvider.resolveOrDefault(descriptor.getTimezone());
      Instant next =
          cronAdapter.next(descriptor.getScheduleExpression(), zoneId, scheduledFireTime);
      if (next == null) {
        log.warn("trigger has no future fire time, leaving stale: job={}", state.getJobCode());
        // next_fire_time 设为遥远未来防止反复扫;实际上业务侧应该 disable
        next = dateTimeSupport.nowInstant().plus(Duration.ofDays(36500));
      }
      stateMapper.advanceAfterFire(
          state.getId(), next, scheduledFireTime, lastFireStatus, misfireDelta);
    } catch (Exception e) {
      log.warn("advanceAfterFire failed for job={}: {}", state.getJobCode(), e.getMessage());
    }
  }

  private TriggerDescriptor loadDescriptor(TriggerRuntimeStateEntity state) {
    return definitionLoader.loadByJobCode(state.getTenantId(), state.getJobCode());
  }

  private static String defaultLeaderInstanceId() {
    String host;
    try {
      host = InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      SwallowedExceptionLogger.warn(HashedWheelTriggerScheduler.class, "catch:Exception", e);

      host = "unknown";
    }
    return host + ":" + UUID.randomUUID().toString().substring(0, 8);
  }
}
