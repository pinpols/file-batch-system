package com.example.batch.console.domain.view.dashboard;

/** dashboard 按 severity 分组的计数投影 (alert_event)。 */
public record SeverityCountView(String severity, Long count) {}
