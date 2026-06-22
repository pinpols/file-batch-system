package com.example.batch.worker.imports.infrastructure.quality;

public record ValidationIssue(
    Long recordNo, String errorCode, String errorMessage, Object rawRecord) {}
