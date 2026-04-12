package com.example.batch.worker.core.support;

import com.example.batch.worker.core.domain.StepExecutionRequest;
import com.example.batch.worker.core.domain.StepExecutionResponse;

public interface StepExecutionAdapter {

  StepExecutionResponse execute(StepExecutionRequest request);
}
