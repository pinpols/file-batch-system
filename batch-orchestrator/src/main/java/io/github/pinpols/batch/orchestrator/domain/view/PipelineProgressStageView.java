package io.github.pinpols.batch.orchestrator.domain.view;

/** 数据库中仍在执行的阶段尝试；并行任务不得按全Pipeline的最大序号合并为一个任务。 */
public record PipelineProgressStageView(Long stepRunId, String stageCode, Integer runSeq) {}
