package io.github.pinpols.batch.console.domain.notification.view;

/** 在首个作业终态回执提交时选定的设备。 */
public record JobPushDelivery(
    Long id,
    String tenantId,
    String terminalEventKey,
    String operatorId,
    Long subscriptionId,
    String subscriptionFingerprint,
    String payloadJson,
    int attempt) {}
