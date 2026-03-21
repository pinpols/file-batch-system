package com.example.batch.worker.imports.stage;

import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;

public interface ImportStageStep {

    ImportStage stage();

    ImportStageResult execute(ImportJobContext context);
}
