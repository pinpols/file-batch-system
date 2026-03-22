package com.example.batch.worker.dispatchs.stage;

import com.example.batch.worker.dispatchs.domain.DispatchJobContext;
import com.example.batch.worker.dispatchs.domain.DispatchStage;
import com.example.batch.worker.dispatchs.domain.DispatchStageResult;

public interface DispatchStageStep {

    DispatchStage stage();

    default String stepCode() {
        return "DISPATCH_" + stage().name();
    }

    default String stepName() {
        return stepCode();
    }

    default String implCode() {
        return stepCode();
    }

    DispatchStageResult execute(DispatchJobContext context);
}
