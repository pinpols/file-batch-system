package com.example.batch.orchestrator.domain.command;

public record TaskOutcomeCommand(
    String tenantId,
    Long taskId,
    String workerId,
    boolean success,
    String resultSummary,
    String errorCode,
    String errorMessage,
    /** Worker BizException.of 的 i18n key;持久化到 job_task.error_key 供 console 重渲染。 */
    String errorKey,
    /** i18n 占位符参数 JSON 数组,与 errorKey 一起跨进程传递。 */
    String errorArgs,
    /**
     * 增量执行模式(ExecutionMode.INCREMENTAL)下,worker 完成时上报的新水位高点。orchestrator 在成功路径下回写 {@code
     * job_instance.high_water_mark_out};非 INCREMENTAL 或 worker 没显式上报时为 null,持久化也跳过(保留旧值,下次实例的 IN
     * 不变即"无进展")。
     */
    String highWaterMarkOut) {}
