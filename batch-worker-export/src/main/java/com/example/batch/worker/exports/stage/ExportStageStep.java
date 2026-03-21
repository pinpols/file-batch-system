package com.example.batch.worker.exports.stage;

import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportStage;
import com.example.batch.worker.exports.domain.ExportStageResult;

public interface ExportStageStep {

    ExportStage stage();

    ExportStageResult execute(ExportJobContext context);
}
