package io.github.pinpols.batch.common.dto;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

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
 *   <li>execution / retry 字段(executionMode / retryPolicy / retryMaxCount / timeoutSeconds) 对新实例从
 *       {@code job_instance.params_snapshot.executionPolicy} 读取，固定实例创建时语义；没有该快照段的历史实例才回退到
 *       {@code job_definition}。
 *   <li>highWaterMarkIn 从 {@code job_instance} 实时读,与 ExecutionMode.INCREMENTAL 配合。
 * </ul>
 *
 * <p>兼容性:Stage 1(本 PR)仅新增,worker 优先读本对象,缺字段时 fallback 到 {@code TaskDispatchMessage}; Stage 2 按
 * schemaVersion v1→v2 瘦身 message 只留 task key。
 */
@Builder
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
    /** 重试策略 code(NONE/FIXED/EXPONENTIAL)，优先读取实例创建时快照。 */
    String retryPolicy,
    /** 最大重试次数，优先读取实例创建时快照。 */
    Integer retryMaxCount,
    /** 超时秒数，优先读取实例创建时快照。 */
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
    /** 分区计划契约版本，当前为 1；null 表示旧平台或非标准分区计划。 */
    Integer partitionPlanVersion,
    /** 当前分片的 0-based 下标，worker 按半开 range 或自定义策略切分时优先使用。 */
    Integer shardIndex,
    /** 本次分片总数，语义等同 partitionCount，但与 shardIndex 组成 0-based worker 契约。 */
    Integer shardTotal,
    /** 半开行号范围起点；仅当平台拿到 expectedRows/recordCount 等总量提示时填充。 */
    Long rangeStartInclusive,
    /** 半开行号范围终点；仅当平台拿到 expectedRows/recordCount 等总量提示时填充。 */
    Long rangeEndExclusive,
    /** 当前分片预期行数；无总量提示时为 null。 */
    Long expectedRows,
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
    String partitionInvocationId,
    /** 门禁观察到的依赖结果标识；消费者必须验证或固定该标识后，才能确认实际使用的结果。 */
    List<DependencyResultReference> dependencyResultSnapshot,
    /** 整 Workflow Replay 在审批快照中固定的操作与能力合同；普通任务为 null。 */
    ReplayExecutionGuard replayExecutionGuard) {

  public EffectiveTaskConfig {
    dependencyResultSnapshot = EmptyChecks.isNull(dependencyResultSnapshot)
        ? List.of()
        : List.copyOf(dependencyResultSnapshot);
  }

  /** 旧 orchestrator 领取成功但未返回实时配置时使用的兼容占位。 */
  public static EffectiveTaskConfig empty() {
    return new EffectiveTaskConfig(
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, List.of(), null);
  }
}
