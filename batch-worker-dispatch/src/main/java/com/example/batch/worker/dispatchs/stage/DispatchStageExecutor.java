package com.example.batch.worker.dispatchs.stage;

import com.example.batch.worker.core.domain.PipelineStepTemplate;
import com.example.batch.worker.dispatchs.domain.DispatchJobContext;
import com.example.batch.worker.dispatchs.domain.DispatchStageResult;
import java.util.List;

public interface DispatchStageExecutor {

    List<DispatchStageResult> execute(DispatchJobContext context);

    List<PipelineStepTemplate> defaultStepDefinitions();
}
