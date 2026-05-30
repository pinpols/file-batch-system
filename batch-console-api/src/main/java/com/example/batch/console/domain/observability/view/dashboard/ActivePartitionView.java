package com.example.batch.console.domain.observability.view.dashboard;

/** dashboard 按 worker_code 聚合的活跃 partition 数 (job_partition READY/RUNNING)。 */
public record ActivePartitionView(String workerCode, Long activePartitions) {}
