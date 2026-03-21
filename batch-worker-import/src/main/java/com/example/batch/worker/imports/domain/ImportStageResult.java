package com.example.batch.worker.imports.domain;

public record ImportStageResult(
        ImportStage stage,
        boolean success,
        String code,
        String message
) {
    public static ImportStageResult success(ImportStage stage) {
        return new ImportStageResult(stage, true, "SUCCESS", "ok");
    }

    public static ImportStageResult failure(ImportStage stage, String code, String message) {
        return new ImportStageResult(stage, false, code, message);
    }
}
