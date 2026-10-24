package io.github.pinpols.batch.console.domain.notification.view;

/** 一个终态事实及其首次冻结的全部投递、人工审计共同构成归档单元。 */
public record TerminalNotificationArchiveCandidate(String tenantId, String eventKey) {}
