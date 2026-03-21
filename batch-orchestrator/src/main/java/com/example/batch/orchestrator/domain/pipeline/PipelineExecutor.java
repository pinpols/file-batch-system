package com.example.batch.orchestrator.domain.pipeline;

public interface PipelineExecutor {

    PipelineExecutionResult execute(PipelineContext context);
}
