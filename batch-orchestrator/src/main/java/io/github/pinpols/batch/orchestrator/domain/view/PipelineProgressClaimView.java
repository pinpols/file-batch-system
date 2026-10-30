package io.github.pinpols.batch.orchestrator.domain.view;

/** 查询时由数据库确认仍有效的任务认领，不能由Worker心跳自证。 */
public record PipelineProgressClaimView(
    Long taskId, String workerCode, String partitionInvocationId) {}
