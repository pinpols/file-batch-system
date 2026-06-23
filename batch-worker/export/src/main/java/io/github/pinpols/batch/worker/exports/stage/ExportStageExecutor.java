package io.github.pinpols.batch.worker.exports.stage;

import io.github.pinpols.batch.worker.core.domain.PipelineStepTemplate;
import io.github.pinpols.batch.worker.exports.domain.ExportJobContext;
import io.github.pinpols.batch.worker.exports.domain.ExportStageResult;
import java.util.List;

public interface ExportStageExecutor {

  List<ExportStageResult> execute(ExportJobContext context);

  List<PipelineStepTemplate> defaultStepDefinitions();
}
