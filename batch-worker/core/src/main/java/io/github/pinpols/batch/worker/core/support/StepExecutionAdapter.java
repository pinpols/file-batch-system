package io.github.pinpols.batch.worker.core.support;

import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.worker.core.domain.StepExecutionRequest;
import io.github.pinpols.batch.worker.core.domain.StepExecutionResponse;

public interface StepExecutionAdapter {

  /** 允许重放执行前，按精确请求配置解析能力。 */
  default ReplayCapabilityDeclaration resolveReplayCapability(StepExecutionRequest request) {
    String pluginId = EmptyChecks.isNull(request) || EmptyChecks.isNull(request.stepCode())
        ? "UNKNOWN"
        : request.stepCode();
    return ReplayCapabilityDeclaration.unknown(pluginId);
  }

  StepExecutionResponse execute(StepExecutionRequest request);
}
