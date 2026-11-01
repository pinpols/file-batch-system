package io.github.pinpols.batch.orchestrator.domain.view;

public record DispatchSecretBindingView(
    String channelCode, String secretRef, Long secretVersionId, Integer versionNo) {}
