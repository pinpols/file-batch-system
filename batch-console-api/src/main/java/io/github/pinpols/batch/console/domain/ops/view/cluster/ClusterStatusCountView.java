package io.github.pinpols.batch.console.domain.ops.view.cluster;

/** 按状态分组的固定列计数投影。 */
public record ClusterStatusCountView(String status, Long count) {}
