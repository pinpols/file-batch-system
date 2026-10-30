package io.github.pinpols.batch.console.domain.job.application.contract.response;

import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;

/** 向客户端投射所选 Worker 组的重放能力证据。 */
public record ConsoleWorkflowReplayCapabilityAssessment(
    String status, ReplayCapabilityDeclaration declaration, int workerCount) {}
