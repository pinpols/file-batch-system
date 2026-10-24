package io.github.pinpols.batch.common.dto;

import io.github.pinpols.batch.common.observability.W3cTraceContext;
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
 *   <li>{@code sourceFireTime} — Quartz 原计划 fire 时刻；非 Quartz 请求为接纳事件时刻，不能用业务数据区间冒充
 *   <li>{@code envelopeVersion} — 持久化消息协议版本；consumer 仅接受当前版本，不将旧载荷降级到旧 Job 根入口
 *   <li>{@code traceContext} — 持久化异步边界所需的 W3C 传输上下文；不承载业务身份
 * </ul>
 *
 * @param launchRequest 完整的 launch 命令参数。顶层 Trigger launch 必须使用
 *     {@code workflowDefinitionId}；JobCode 仅用于 Workflow 节点派发等内部作业上下文。
 * @param dedupKey trigger 端已计算的去重 key(orchestrator 仍会通过 uk_job_instance_tenant_dedup 回退)
 * @param sourceFireTime Quartz 原计划时刻或非 Quartz 接纳事件时刻，UTC instant
 * @param envelopeVersion 协议版本号,当前 2
 */
public record LaunchEnvelope(
    LaunchRequest launchRequest,
    String dedupKey,
    Instant sourceFireTime,
    int envelopeVersion,
    W3cTraceContext traceContext) {

  /** 当前协议版本。 */
  public static final int CURRENT_VERSION = 2;

  /** 工厂方法:用当前版本号 + now() 构造,业务路径默认调用此方法。 */
  public static LaunchEnvelope of(
      LaunchRequest launchRequest, String dedupKey, Instant sourceFireTime) {
    return new LaunchEnvelope(launchRequest, dedupKey, sourceFireTime, CURRENT_VERSION, null);
  }

  public LaunchEnvelope withTraceContext(W3cTraceContext context) {
    return new LaunchEnvelope(launchRequest, dedupKey, sourceFireTime, envelopeVersion, context);
  }
}
