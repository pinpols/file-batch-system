package io.github.pinpols.batch.common.dto;

import java.time.Instant;

/**
 * ADR-010: trigger → orchestrator 异步事件载荷。
 *
 * <p>trigger 在 fire 时把 {@link LaunchRequest} 包成 envelope 落 trigger_outbox_event 表,
 * TriggerOutboxRelay 序列化成 JSON 发到 Kafka topic {@code batch.trigger.launch.v1};orchestrator 端
 * TriggerLaunchConsumer 反序列化后调用现有 LaunchApplicationService.launch 内部 API。
 *
 * <p>携带的额外字段:
 *
 * <ul>
 *   <li>{@code dedupKey} — trigger 已计算过的 dedup key,避免 orchestrator 重复算
 *   <li>{@code sourceFireTime} — Quartz / wheel 实际 fire 时刻,supports 链路审计与 latency 统计
 *   <li>{@code envelopeVersion} — 协议演进时 consumer 兼容多版本(v1 现行)
 * </ul>
 *
 * @param launchRequest 完整的 launch
 *     命令参数(tenantId/jobCode/bizDate/triggerType/requestId/traceId/params)
 * @param dedupKey trigger 端已计算的去重 key(orchestrator 仍会通过 uk_job_instance_tenant_dedup 回退)
 * @param sourceFireTime trigger 实际 fire 时刻,UTC instant
 * @param envelopeVersion 协议版本号,当前 1
 */
public record LaunchEnvelope(
    LaunchRequest launchRequest, String dedupKey, Instant sourceFireTime, int envelopeVersion) {

  /** 当前协议版本。 */
  public static final int CURRENT_VERSION = 1;

  /** 工厂方法:用当前版本号 + now() 构造,业务路径默认调用此方法。 */
  public static LaunchEnvelope of(
      LaunchRequest launchRequest, String dedupKey, Instant sourceFireTime) {
    return new LaunchEnvelope(launchRequest, dedupKey, sourceFireTime, CURRENT_VERSION);
  }
}
