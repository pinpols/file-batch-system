package com.example.batch.orchestrator.domain.pipeline;

public interface PipelineExecutor {

  PipelineExecutionResult execute(ExecutionContext context);
}
