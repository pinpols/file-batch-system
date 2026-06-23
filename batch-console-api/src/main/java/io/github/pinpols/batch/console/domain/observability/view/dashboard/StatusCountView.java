package io.github.pinpols.batch.console.domain.observability.view.dashboard;

/** dashboard 按 status 分组的计数投影 (job_instance / worker_registry 通用)。 */
public record StatusCountView(String status, Long count) {}
