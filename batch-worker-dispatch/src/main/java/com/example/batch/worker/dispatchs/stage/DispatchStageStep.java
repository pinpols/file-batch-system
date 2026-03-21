package com.example.batch.worker.dispatchs.stage;

import com.example.batch.worker.dispatchs.domain.DispatchJobContext;
import com.example.batch.worker.dispatchs.domain.DispatchStage;
import com.example.batch.worker.dispatchs.domain.DispatchStageResult;

public interface DispatchStageStep {

    DispatchStage stage();

    DispatchStageResult execute(DispatchJobContext context);
}
