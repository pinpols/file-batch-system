package com.example.batch.worker.imports.stage;

import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;

public interface ImportStageStep {

    ImportStage stage();

    default String stepCode() {
        return "IMPORT_" + stage().name();
    }

    default String stepName() {
        return stepCode();
    }

    default String implCode() {
        return stepCode();
    }

    ImportStageResult execute(ImportJobContext context);
}
