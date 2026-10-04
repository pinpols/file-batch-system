package io.github.pinpols.batch.common.dto;

/**
 * 内置 Worker 在心跳中上报的单任务阶段进度。
 *
 * <p>任务、pipeline 实例和 stage 三个标识必须同时存在，控制面才能在同一 JVM 并发执行多个分片时正确聚合，避免沿用
 * workerCode 单槽导致不同任务相互覆盖。
 */
public record WorkerPipelineProgressDto(
    Long taskId,
    Long pipelineInstanceId,
    String stageCode,
    Long rowsProcessed,
    Long totalRowsHint) {}
