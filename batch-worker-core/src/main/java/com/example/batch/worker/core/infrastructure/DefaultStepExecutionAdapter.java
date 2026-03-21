package com.example.batch.worker.core.infrastructure;

import com.example.batch.worker.core.domain.StepExecutionRequest;
import com.example.batch.worker.core.domain.StepExecutionResponse;
import com.example.batch.worker.core.support.StepExecutionAdapter;
import org.springframework.stereotype.Component;

@Component
public class DefaultStepExecutionAdapter implements StepExecutionAdapter {

    @Override
    public StepExecutionResponse execute(StepExecutionRequest request) {
        return StepExecutionResponse.successResponse();
    }
}
