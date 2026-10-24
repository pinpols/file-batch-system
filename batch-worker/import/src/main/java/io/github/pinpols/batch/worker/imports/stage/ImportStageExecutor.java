package io.github.pinpols.batch.worker.imports.stage;

import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import io.github.pinpols.batch.worker.core.domain.PipelineStepTemplate;
import io.github.pinpols.batch.worker.core.support.PipelineStepTemplateProvider;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportStageResult;
import io.github.pinpols.batch.worker.imports.domain.ImportWorkerType;
import java.util.List;

public interface ImportStageExecutor extends PipelineStepTemplateProvider {

  List<ImportStageResult> execute(ImportJobContext context);

  /** 使用 RECEIVE 阶段选定的同一冻结模板解析 LOAD 重放安全性。 */
  default ReplayCapabilityDeclaration resolveReplayCapability(ImportJobContext context) {
    return ReplayCapabilityDeclaration.unknown(ImportWorkerType.IMPORT);
  }

  @Override
  List<PipelineStepTemplate> defaultStepDefinitions();
}
