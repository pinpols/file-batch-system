package com.example.batch.sdk.scheduler;

import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.example.batch.sdk.client.WorkerIdentity;
import com.example.batch.sdk.dispatcher.HeartbeatDirective;
import com.example.batch.sdk.dispatcher.TaskDispatcher;
import com.example.batch.sdk.internal.PlatformHttpClient;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * 心跳上报 — 按 {@link BatchPlatformClientConfig#getHeartbeatInterval()} 周期 POST {@code
 * /internal/workers/{workerCode}/heartbeat}。
 *
 * <p>body 对齐 {@code WorkerHeartbeatDto}:{@code tenantId / workerCode / status / heartbeatAt /
 * currentLoad / capabilityTags}。HTTP 失败不抛(orchestrator 端 missed-heartbeat 阈值回退)。
 */
@Slf4j
public class HeartbeatScheduler implements AutoCloseable {

  /** Lane I:hint 回退下限 — orch 配错(如 0 / 100ms)会把心跳刷爆,强制至少 1s。 */
  static final long MIN_HINT_MS = 1_000L;

  /** Lane I:hint 回退上限倍率 — hint 不得超过 baseline 的 10 倍,避免 orch 配错把心跳拖死。 */
  static final long MAX_HINT_MULTIPLIER = 10L;

  private final BatchPlatformClientConfig config;
  private final PlatformHttpClient httpClient;
  private final TaskDispatcher dispatcher;
  private final WorkerIdentity identity;
  private final ScheduledExecutorService scheduler;

  /** Lane I:当前 schedule 的 future,收到 hint 时先 cancel 再重排。 */
  private final AtomicReference<ScheduledFuture<?>> currentFuture = new AtomicReference<>();

  /** Lane I:当前生效心跳间隔(ms),供单测/可观测查询;首次 start 时初始化为 config baseline。 */
  private final AtomicLong currentIntervalMs = new AtomicLong();

  public HeartbeatScheduler(
      BatchPlatformClientConfig config, PlatformHttpClient httpClient, TaskDispatcher dispatcher) {
    this(config, httpClient, dispatcher, null, defaultScheduler());
  }

  /**
   * 带 {@link WorkerIdentity} 的构造器 — 业务侧应优先用这个,确保 heartbeat 与 Python SDK PR #320 对齐(6 字段:
   * workerGroup/hostName/hostIp/processId/capabilityTags/buildId)。
   */
  public HeartbeatScheduler(
      BatchPlatformClientConfig config,
      PlatformHttpClient httpClient,
      TaskDispatcher dispatcher,
      WorkerIdentity identity) {
    this(config, httpClient, dispatcher, identity, defaultScheduler());
  }

  // 包内可见 — 单测注入 mock ScheduledExecutorService 验证 fixed-delay vs fixed-rate
  HeartbeatScheduler(
      BatchPlatformClientConfig config,
      PlatformHttpClient httpClient,
      TaskDispatcher dispatcher,
      WorkerIdentity identity,
      ScheduledExecutorService scheduler) {
    this.config = config;
    this.httpClient = httpClient;
    this.dispatcher = dispatcher;
    this.identity = identity;
    this.scheduler = scheduler;
  }

  private static ScheduledExecutorService defaultScheduler() {
    return Executors.newSingleThreadScheduledExecutor(
        r -> {
          Thread t = new Thread(r, "batch-sdk-heartbeat");
          t.setDaemon(true);
          return t;
        });
  }

  public void start() {
    long intervalMs = config.getHeartbeatInterval().toMillis();
    currentIntervalMs.set(intervalMs);
    // fixed-delay 而非 fixed-rate:平台短暂卡顿后,SDK 不应追赶式连发心跳把 orchestrator 雪崩,
    // 而是每次 tick 完成后再等一个完整 interval。见 #SDK-P1-3。
    ScheduledFuture<?> f =
        scheduler.scheduleWithFixedDelay(this::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    currentFuture.set(f);
    log.info("HeartbeatScheduler started: interval={}ms (fixed-delay)", intervalMs);
  }

  void tick() {
    HeartbeatDirective directive = null;
    try {
      Map<String, Object> body = new HashMap<>();
      body.put("tenantId", config.getTenantId());
      body.put("workerCode", config.getWorkerCode());
      body.put("status", "RUNNING");
      body.put("heartbeatAt", Instant.now().toString());
      body.put("currentLoad", dispatcher.inFlightCount());
      // Lane I / Python SDK PR #320 对齐:把 register 时已上报的 6 字段也带到每次 heartbeat。
      // 动机:DefaultWorkerRegistryService#heartbeat 在 registry 行不存在时会回退降级到 register
      // 路径(运维误删 / 平台冷启重建索引等场景),那一刻若 body 缺这些字段,worker_registry 行
      // 就会带 null 字段重建。把字段每次都带上消除该窗口。null 字段由 Jackson NON_NULL 略过,平台
      // 端 record 允许 null。兼容性说明:platform 端 heartbeat 路径目前仅消费 status/load/capabilityTags,
      // workerGroup/host*/processId/buildId 暂不刷新 worker_registry(见
      // DefaultWorkerRegistryService#heartbeat),后续 ORCH-P5 把这些列也纳入 touchHeartbeat。
      if (identity != null) {
        putIfPresent(body, "workerGroup", identity.workerGroup());
        putIfPresent(body, "hostName", identity.hostName());
        putIfPresent(body, "hostIp", identity.hostIp());
        putIfPresent(body, "processId", identity.processId());
        putIfPresent(body, "buildId", identity.buildId());
        if (identity.capabilityTags() != null && !identity.capabilityTags().isEmpty()) {
          body.put("capabilityTags", identity.capabilityTags());
        }
      }
      Map<String, Object> resp = httpClient.heartbeat(config.getWorkerCode(), body);
      // Phase 2 §2.4:回包是 platform directive,据此驱动 dispatcher 4 态状态机
      directive = HeartbeatDirective.fromResponse(resp);
      dispatcher.applyPlatformDirective(directive);
    } catch (Throwable t) {
      // 不能让心跳异常杀掉 scheduler — fixed-rate 一旦抛会停
      log.warn("heartbeat failed: {}", t.getMessage());
    }
    // Lane I (ADR-035 §11):若 orch 下发 nextHeartbeatHint,据此动态重排下次心跳间隔。
    // 回退:hint < 1s → 1s;hint > 10 × baseline → 10 × baseline(防 orch 配错)。
    // null = 不下发,保持当前间隔(向后兼容老平台 / 老回包)。
    if (directive != null && directive.nextHeartbeatHint() != null) {
      applyHeartbeatHint(directive.nextHeartbeatHint().longValue() * 1_000L);
    }
  }

  /** Lane I:据 hint 重排心跳调度;hint 单位 ms,内含回退。包内可见以便单测。 */
  void applyHeartbeatHint(long hintMs) {
    long baselineMs = config.getHeartbeatInterval().toMillis();
    long maxMs = baselineMs * MAX_HINT_MULTIPLIER;
    long clamped = Math.max(MIN_HINT_MS, Math.min(maxMs, hintMs));
    long prev = currentIntervalMs.get();
    if (clamped == prev) {
      return; // 间隔无变化,无需重排
    }
    if (!currentIntervalMs.compareAndSet(prev, clamped)) {
      return; // 并发竞争,后者赢就行
    }
    ScheduledFuture<?> old = currentFuture.get();
    if (old != null) {
      old.cancel(false);
    }
    ScheduledFuture<?> next =
        scheduler.scheduleWithFixedDelay(this::tick, clamped, clamped, TimeUnit.MILLISECONDS);
    currentFuture.set(next);
    log.info(
        "HeartbeatScheduler re-scheduled by orch hint: {}ms -> {}ms (raw hint={}ms, baseline={}ms)",
        prev,
        clamped,
        hintMs,
        baselineMs);
  }

  /** 当前生效心跳间隔(ms),包内可见供单测断言。 */
  long currentIntervalMs() {
    return currentIntervalMs.get();
  }

  private static void putIfPresent(Map<String, Object> body, String key, String value) {
    if (value != null && !value.isBlank()) {
      body.put(key, value);
    }
  }

  @Override
  public void close() {
    log.info("HeartbeatScheduler stopping");
    ScheduledFuture<?> f = currentFuture.getAndSet(null);
    if (f != null) {
      f.cancel(false);
    }
    scheduler.shutdown();
    try {
      if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
        scheduler.shutdownNow();
      }
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      scheduler.shutdownNow();
    }
  }
}
