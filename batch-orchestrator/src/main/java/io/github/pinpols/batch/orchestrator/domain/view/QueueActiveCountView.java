package io.github.pinpols.batch.orchestrator.domain.view;

/** 队列的活跃作业固定聚合投影。 */
public record QueueActiveCountView(String queueCode, Long cnt) {}
