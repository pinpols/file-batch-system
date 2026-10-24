package io.github.pinpols.batch.trigger.domain;

public record ExternalEventLaunchTarget(Long workflowDefinitionId, String requestId) {}
