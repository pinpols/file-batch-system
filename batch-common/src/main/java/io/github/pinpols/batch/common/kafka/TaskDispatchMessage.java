package io.github.pinpols.batch.common.kafka;

import java.time.Instant;

/**
 * Kafka task 派发消息。
 *
 * <p>schema v2(P1-2.2 起):瘦身只保留 task key + 路由元数据,业务字段(payload/businessKey/taskSeq/ highWaterMarkIn
 * 等)统一走 worker CLAIM 时返回的 {@link io.github.pinpols.batch.common.dto.EffectiveTaskConfig} 实时读
 * DB,确保管理员改 retry/timeout/payload 等配置后立即生效,不再受队列里旧消息延迟。
 *
 * <p>设计依据:{@code docs/design/batch-classification-and-gaps.md} §3.4 / §4 P1-2 / §4.4。
 *
 * <p>保留字段分两类:
 *
 * <ul>
 *   <li><b>task key</b>:tenantId / taskId / jobInstanceId / jobPartitionId / partitionNo /
 *       partitionCount / instanceNo / jobCode / traceId / idempotencyKey / dispatchAt — worker
 *       CLAIM 必需的定位 + 幂等 + 链路追踪
 *   <li><b>路由元数据</b>:workerType(消费端 accepts 过滤)、selectedWorkerId(direct dispatch topic)、
 *       priorityBand(producer PRIORITY 模式 topic 后缀) — 派发链路必需,不依赖 DB 重读
 * </ul>
 *
 * <p>已删字段(v1 → v2):{@code taskType}(与 workerType 同值,冗余)、{@code taskSeq}、 {@code businessKey}、
 * {@code payload}、{@code highWaterMarkIn} — 全部移到 EffectiveTaskConfig。
 *
 * <p>兼容性:Jackson 反序列化未知字段被忽略,旧 v1 消息(含已删字段)被新 worker 解析为 v2 时已删字段直接丢弃, 业务字段从 claim 拿;旧
 * worker(P1-2.1 之前)期望旧字段 → P1-2.2 之前已经全量切到 P1-2.1 worker, 而 P1-2.1 worker 已优先用 claim
 * response,因此即使收到 v2 消息也能正常工作。
 */
public record TaskDispatchMessage(
    String schemaVersion,
    String tenantId,
    Long jobInstanceId,
    Long jobPartitionId,
    Long taskId,
    String instanceNo,
    String jobCode,
    /** 路由元数据:worker 端 {@code accepts()} 按此过滤;通常等同 {@code job_task.task_type}。 */
    String workerType,
    /** 路由元数据:非 null 表示 orchestrator 已选定 worker,publisher 走 direct dispatch topic。 */
    String selectedWorkerId,
    /** 路由元数据:HIGH/MEDIUM/LOW;producer 端 PRIORITY 模式拼 topic 后缀。 */
    String priorityBand,
    String traceId,
    String idempotencyKey,
    Instant dispatchAt,
    /**
     * SDK Phase 2 §2.1:派单时刻确定的调度上下文(bizDate / 前后业务日 / 重试序号 / 触发来源)。 这些是任务的不可变事实,随消息下沉供 SDK handler
     * 直接读取,无需回调平台查。可空(老消息 / 无上下文场景)。
     */
    SchedulingContext schedulingContext,
    /**
     * 逻辑分片序号(从 1 开始)。仅用于 producer 侧 Kafka key 分散和观测；worker CLAIM 仍以 DB EffectiveTaskConfig
     * 为准，老消息缺字段时可为空。
     */
    Integer partitionNo,
    /** 本次 job 的逻辑分片总数。与 {@link #partitionNo} 一起用于稳定分散 Kafka 分区。 */
    Integer partitionCount) {

  @SuppressWarnings("PMD.ExcessiveParameterList")
  public TaskDispatchMessage(
      String schemaVersion,
      String tenantId,
      Long jobInstanceId,
      Long jobPartitionId,
      Long taskId,
      String instanceNo,
      String jobCode,
      String workerType,
      String selectedWorkerId,
      String priorityBand,
      String traceId,
      String idempotencyKey,
      Instant dispatchAt,
      SchedulingContext schedulingContext) {
    this(
        schemaVersion,
        tenantId,
        jobInstanceId,
        jobPartitionId,
        taskId,
        instanceNo,
        jobCode,
        workerType,
        selectedWorkerId,
        priorityBand,
        traceId,
        idempotencyKey,
        dispatchAt,
        schedulingContext,
        null,
        null);
  }
}
