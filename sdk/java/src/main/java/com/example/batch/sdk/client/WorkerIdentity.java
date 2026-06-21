package com.example.batch.sdk.client;

import java.util.List;

/**
 * Worker 运行身份快照 — register 时确定,heartbeat 周期内不变。
 *
 * <p>用于 {@link com.example.batch.sdk.scheduler.HeartbeatScheduler} 在每次 tick 时把这 6 个字段写入 heartbeat
 * body, 与 Python SDK PR #320 对齐:
 *
 * <ul>
 *   <li>{@code workerGroup}:SDK 自托管固定为 {@code "sdk-self-hosted"}(ADR-035 §2)
 *   <li>{@code hostName} / {@code hostIp} / {@code processId}:{@link WorkerFingerprint} 尽力采集
 *   <li>{@code capabilityTags}:已注册 handler 的 taskType 集合
 *   <li>{@code buildId}:租户经 {@link BatchPlatformClientConfig#getBuildId()} 注入
 * </ul>
 *
 * <p>历史上 SDK 在 heartbeat 仅发 5 字段(tenantId/workerCode/status/heartbeatAt/currentLoad),依赖 "平台从
 * register 拿"的隐式约定。但 worker 长跑后若 worker_registry 行被运维误删 / 平台冷启动重建索引,heartbeat 回退降级到 register
 * 路径({@code DefaultWorkerRegistryService#heartbeat} 中 {@code registry == null →
 * self.register(request)})时这些字段就丢了 — 把它们带在每次心跳里消除该窗口。
 *
 * <p>序列化策略:{@code WorkerHeartbeatDto} 字段为 {@code null} 时 jackson 走 NON_NULL 路径不发, 平台端 record 直接接收
 * null;调用方传 null 字段安全。
 */
public record WorkerIdentity(
    String workerGroup,
    String hostName,
    String hostIp,
    String processId,
    List<String> capabilityTags,
    String buildId) {

  public WorkerIdentity {
    capabilityTags = capabilityTags == null ? List.of() : List.copyOf(capabilityTags);
  }
}
