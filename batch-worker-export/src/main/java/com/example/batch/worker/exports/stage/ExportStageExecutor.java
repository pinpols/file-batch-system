package com.example.batch.worker.exports.stage;

import com.example.batch.worker.core.domain.PipelineStepTemplate;
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportStageResult;
import java.util.List;

public interface ExportStageExecutor {

  List<ExportStageResult> execute(ExportJobContext context);

  List<PipelineStepTemplate> defaultStepDefinitions();
}
