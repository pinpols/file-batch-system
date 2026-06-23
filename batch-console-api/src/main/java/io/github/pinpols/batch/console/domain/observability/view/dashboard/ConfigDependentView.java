package io.github.pinpols.batch.console.domain.observability.view.dashboard;

/** dashboard 反查 — 给定 config code 找出依赖该 config 的 job_definition 列表。 */
public record ConfigDependentView(Long id, String code, String name) {}
