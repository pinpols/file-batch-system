package io.github.pinpols.batch.orchestrator.domain.query;

/** A claimed durable Workflow launch intent, before a root run has been admitted. */
public record WorkflowLaunchWaitClaim(String tenantId, String requestId, String launchPayload) {}
