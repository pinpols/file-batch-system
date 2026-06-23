package io.github.pinpols.batch.orchestrator.domain.pipeline;

public interface PipelineExecutor {

  PipelineExecutionResult execute(ExecutionContext context);
}
