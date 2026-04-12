package com.example.batch.worker.core.domain;

public record WorkerExecutionResult(String taskId, boolean success, String message) {}
