package com.example.batch.sdk.scheduler;

import com.example.batch.sdk.client.BatchPlatformClientConfig;
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
    this.config = config;
    this.httpClient = httpClient;
    this.dispatcher = dispatcher;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "batch-sdk-heartbeat");
              t.setDaemon(true);
              return t;
            });
  }

  public void start() {
    long intervalMs = config.getHeartbeatInterval().toMillis();
    scheduler.scheduleAtFixedRate(this::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    log.info("HeartbeatScheduler started: interval={}ms", intervalMs);
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
      httpClient.heartbeat(config.getWorkerCode(), body);
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
