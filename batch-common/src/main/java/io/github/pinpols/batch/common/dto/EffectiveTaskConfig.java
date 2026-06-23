package io.github.pinpols.batch.common.dto;

import java.time.Instant;

/**
 * Worker CLAIM 任务时由 orchestrator 返回的"生效配置快照"。
 *
 * <p>设计依据:{@code docs/design/batch-classification-and-gaps.md} §3.4 / §4 P1-2。 历史上 worker 拿到
 * effective config 走两个通道:一部分嵌在 Kafka {@code TaskDispatchMessage}里、 一部分通过 {@code
 * TaskExecutionClient.claim()} 拉。不统一的代价是管理员改了 {@code
 * JobDefinition.retryMaxCount},下一次任务派发时消息里的旧值仍生效,直到队列清空才会切到新值。
 *
 * <p>P1-2.1 阶段把 claim() 升级为返回完整 effective config:
 *
 * <ul>
 *   <li>identity / payload 字段(jobCode / taskType / payload / businessKey 等)与消息内容等价, 但来自
 *       orchestrator 实时读 DB,不会受队列里旧消息影响。
 *   <li>execution / retry 字段(executionMode / retryPolicy / retryMaxCount / timeoutSeconds) 从 {@code
 *       job_definition} 实时读,管理员改完立即生效。
 *   <li>highWaterMarkIn 从 {@code job_instance} 实时读,与 ExecutionMode.INCREMENTAL 配合。
 * </ul>
 *
 * <p>兼容性:Stage 1(本 PR)仅新增,worker 优先读本对象,缺字段时 fallback 到 {@code TaskDispatchMessage}; Stage 2 按
 * schemaVersion v1→v2 瘦身 message 只留 task key。
 */
public record EffectiveTaskConfig(
    String tenantId,
    Long taskId,
    Long jobInstanceId,
    Long jobPartitionId,
    String instanceNo,
    String jobCode,
    String taskType,
    Integer taskSeq,
    /** Worker 路由维度(等同 message.workerType,通常与 taskType 同值)。 */
    String workerType,
    /** SchedulingPriorityBand code(HIGH/MEDIUM/LOW)。 */
    String priorityBand,
    String businessKey,
    String idempotencyKey,
    /** 业务参数 JSON(等同 message.payload,但实时读 job_task.task_payload)。 */
    String payload,
    String traceId,
    /** ExecutionMode code(FULL/INCREMENTAL/CDC)。 */
    String executionMode,
    /** 增量水位字段名(如 update_time);ExecutionMode=INCREMENTAL 时由 worker 拼 SQL 用。 */
    String watermarkField,
    /** 增量水位起点(从 job_instance.high_water_mark_in 实时读)。 */
    String highWaterMarkIn,
    /** 重试策略 code(NONE/FIXED/EXPONENTIAL),实时反映管理员最新配置。 */
    String retryPolicy,
    /** 最大重试次数,实时反映管理员最新配置。 */
    Integer retryMaxCount,
    /** 超时秒数,实时反映管理员最新配置。 */
    Integer timeoutSeconds,
    /**
     * 当前 task 所属 partition 的 1-based 序号(读自 {@code job_partition.partition_no})。Worker step / plugin
     * 据此 + {@link #partitionCount} 决定自己处理哪部分数据(行 mod / 字节 range / 业务键 hash 等),具体切分维度由 worker
     * 端实现解释。{@code job_type=NONE} 时为 1;不分片场景与单 partition 等价。
     */
    Integer partitionNo,
    /**
     * 本次 job_instance 的 partition 总数(读自 {@code job_instance.expected_partition_count})。Worker 计算"我是
     * N 中的第 K 个"用。
     */
    Integer partitionCount,
    /**
     * partition 业务标识(读自 {@code job_partition.partition_key},默认 {@code jobCode:bizDate:partitionNo}
     * 由 orchestrator 生成,业务可在 plan-build 阶段覆盖为机构号 / hash 桶等)。Worker 端按业务字段切分时读它。
     */
    String partitionKey,
    /**
     * V94: data_interval 半开区间起点 (Airflow 风格). null 表示 trigger 没算 / API 没传, worker 业务用 bizDate 回退退化为
     * {@code [bizDate.atStartOfDay, bizDate+1.atStartOfDay)}.
     */
    Instant dataIntervalStart,
    /** V94: data_interval 半开区间终点. null 时业务退化为 bizDate+1 天. */
    Instant dataIntervalEnd,
    /**
     * ADR-014: 本轮分区认领 invocation id（读 {@code job_partition.current_invocation_id}）。worker
     * renew/report 可选带上做幂等与过期 worker 隔离。
     */
    String partitionInvocationId) {}
