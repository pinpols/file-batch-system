package io.github.pinpols.batch.worker.exports.stage;

import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import io.github.pinpols.batch.worker.core.domain.PipelineStepTemplate;
import io.github.pinpols.batch.worker.core.support.PipelineStepTemplateProvider;
import io.github.pinpols.batch.worker.exports.domain.ExportJobContext;
import io.github.pinpols.batch.worker.exports.domain.ExportStageResult;
import java.util.List;

public interface ExportStageExecutor extends PipelineStepTemplateProvider {

  List<ExportStageResult> execute(ExportJobContext context);

  default ReplayCapabilityDeclaration resolveReplayCapability(ExportJobContext context) {
    return ReplayCapabilityDeclaration.unknown("EXPORT");
  }

  @Override
  List<PipelineStepTemplate> defaultStepDefinitions();
}
