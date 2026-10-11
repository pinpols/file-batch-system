package io.github.pinpols.batch.orchestrator.domain.entity;

public record DispatchSecretBindingEntity(
    String channelCode, String secretRef, Long secretVersionId, Integer versionNo) {}
