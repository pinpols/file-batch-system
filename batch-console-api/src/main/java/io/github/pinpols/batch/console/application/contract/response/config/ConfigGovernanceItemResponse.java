package io.github.pinpols.batch.console.application.contract.response.config;

public record ConfigGovernanceItemResponse(
    String id,
    String className,
    String prefix,
    String source,
    String activation,
    String sensitivity,
    boolean restartRequired) {}
