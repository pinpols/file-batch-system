package io.github.pinpols.batch.worker.imports.stage;

import io.github.pinpols.batch.worker.core.domain.PipelineStepTemplate;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportStageResult;
import java.util.List;

public interface ImportStageExecutor {

  List<ImportStageResult> execute(ImportJobContext context);

  List<PipelineStepTemplate> defaultStepDefinitions();
}
