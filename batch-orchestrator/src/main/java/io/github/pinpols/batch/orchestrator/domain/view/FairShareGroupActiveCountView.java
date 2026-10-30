package io.github.pinpols.batch.orchestrator.domain.view;

/** 公平共享组的活跃作业固定聚合投影。 */
public record FairShareGroupActiveCountView(String fairShareGroup, Long cnt) {}
