package com.example.batch.sdk.scheduler;

import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.example.batch.sdk.dispatcher.HeartbeatDirective;
import com.example.batch.sdk.dispatcher.TaskDispatcher;
import com.example.batch.sdk.internal.PlatformHttpClient;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * 心跳上报 — 按 {@link BatchPlatformClientConfig#getHeartbeatInterval()} 周期 POST {@code
 * /internal/workers/{workerCode}/heartbeat}。
 *
 * <p>body 对齐 {@code WorkerHeartbeatDto}:{@code tenantId / workerCode / status / heartbeatAt /
 * currentLoad / capabilityTags}。HTTP 失败不抛(orchestrator 端 missed-heartbeat 阈值兜底)。
 */
@Slf4j
public class HeartbeatScheduler implements AutoCloseable {

  private final BatchPlatformClientConfig config;
  private final PlatformHttpClient httpClient;
  private final TaskDispatcher dispatcher;
  private final ScheduledExecutorService scheduler;

  public HeartbeatScheduler(
      BatchPlatformClientConfig config, PlatformHttpClient httpClient, TaskDispatcher dispatcher) {
    this(config, httpClient, dispatcher, defaultScheduler());
  }

  // 包内可见 — 单测注入 mock ScheduledExecutorService 验证 fixed-delay vs fixed-rate
  HeartbeatScheduler(
      BatchPlatformClientConfig config,
      PlatformHttpClient httpClient,
      TaskDispatcher dispatcher,
      ScheduledExecutorService scheduler) {
    this.config = config;
    this.httpClient = httpClient;
    this.dispatcher = dispatcher;
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
    // fixed-delay 而非 fixed-rate:平台短暂卡顿后,SDK 不应追赶式连发心跳把 orchestrator 雪崩,
    // 而是每次 tick 完成后再等一个完整 interval。见 #SDK-P1-3。
    scheduler.scheduleWithFixedDelay(this::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    log.info("HeartbeatScheduler started: interval={}ms (fixed-delay)", intervalMs);
  }

  void tick() {
    try {
      Map<String, Object> body = new HashMap<>();
      body.put("tenantId", config.getTenantId());
      body.put("workerCode", config.getWorkerCode());
      body.put("status", "RUNNING");
      body.put("heartbeatAt", Instant.now().toString());
      body.put("currentLoad", dispatcher.inFlightCount());
      // capabilityTags 留空(可选);workerGroup/hostName/hostIp/processId 平台从 register 拿
      Map<String, Object> resp = httpClient.heartbeat(config.getWorkerCode(), body);
      // Phase 2 §2.4:回包是 platform directive,据此驱动 dispatcher 4 态状态机
      dispatcher.applyPlatformDirective(HeartbeatDirective.fromResponse(resp));
    } catch (Throwable t) {
      // 不能让心跳异常杀掉 scheduler — fixed-rate 一旦抛会停
      log.warn("heartbeat failed: {}", t.getMessage());
    }
  }

  @Override
  public void close() {
    log.info("HeartbeatScheduler stopping");
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
