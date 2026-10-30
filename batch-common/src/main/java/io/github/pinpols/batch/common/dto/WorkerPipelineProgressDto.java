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
    Long totalRowsHint,
    String partitionInvocationId,
    Long pipelineStepRunId) {
  public WorkerPipelineProgressDto(
      Long taskId,
      Long pipelineInstanceId,
      String stageCode,
      Long rowsProcessed,
      Long totalRowsHint,
      String partitionInvocationId) {
    this(
        taskId,
        pipelineInstanceId,
        stageCode,
        rowsProcessed,
        totalRowsHint,
        partitionInvocationId,
        null);
  }
  /** 兼容旧源码构造；缺认领身份的样本不会进入对外实时聚合。 */
  public WorkerPipelineProgressDto(
      Long taskId,
      Long pipelineInstanceId,
      String stageCode,
      Long rowsProcessed,
      Long totalRowsHint) {
    this(taskId, pipelineInstanceId, stageCode, rowsProcessed, totalRowsHint, null, null);
  }
}
