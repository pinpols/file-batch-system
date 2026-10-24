package io.github.pinpols.batch.orchestrator.application.service.workflow;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;

/** Launches an explicit top-level Workflow target without creating a synthetic Job Instance. */
public interface WorkflowRunLaunchService {

  LaunchResponse launch(LaunchRequest request);
}
