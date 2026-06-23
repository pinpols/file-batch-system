package io.github.pinpols.batch.console.domain.observability.view.dashboard;

/** dashboard 按 type 分组的计数投影 (trigger_request 按 trigger_type 等)。 */
public record TypeCountView(String type, Long count) {}
