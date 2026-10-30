package io.github.pinpols.batch.console.domain.job.view;

/** 租户就绪检查所需的作业队列引用投影。 */
public record JobDefinitionQueueRefView(String jobCode, String queueCode) {}
