package com.example.batch.sdk.scheduler;

import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.example.batch.sdk.dispatcher.TaskDispatcher;
import com.example.batch.sdk.internal.PlatformHttpClient;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * 心跳上报 — 按 {@link BatchPlatformClientConfig#getHeartbeatInterval()} 周期 POST {@code
 * /internal/workers/heartbeat}。
 *
 * <p>body 含 {@code workerCode / tenantId / inFlightTaskCount / maxConcurrent / status},让
 * orchestrator 知道这 worker 是活的、容量情况。HTTP 失败不抛(orchestrator 端 missed-heartbeat 阈值兜底)。
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
      body.put("inFlightTaskCount", dispatcher.inFlightCount());
      body.put("maxConcurrentTasks", config.getMaxConcurrentTasks());
      body.put("status", "healthy");
      httpClient.heartbeat(body);
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
