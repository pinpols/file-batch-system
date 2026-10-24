package io.github.pinpols.batch.orchestrator.application.service.workflow;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;

/** 启动明确的顶层 Workflow 目标，不创建虚构的 Job Instance。 */
public interface WorkflowRunLaunchService {

  LaunchResponse launch(LaunchRequest request);
}
