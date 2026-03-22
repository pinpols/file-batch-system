package com.example.batch.worker.imports.stage;

import com.example.batch.worker.core.domain.PipelineStepTemplate;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportStageResult;
import java.util.List;

public interface ImportStageExecutor {

    List<ImportStageResult> execute(ImportJobContext context);

    List<PipelineStepTemplate> defaultStepDefinitions();
}
