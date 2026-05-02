package com.example.batch.console.domain.view.dashboard;

/** dashboard 按 worker_group × status 双维度的计数投影 (worker_registry 分组健康)。 */
public record WorkerGroupStatusCountView(String workerGroup, String status, Long count) {}
