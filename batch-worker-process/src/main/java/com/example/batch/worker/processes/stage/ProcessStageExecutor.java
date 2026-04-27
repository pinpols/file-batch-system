package com.example.batch.worker.processes.stage;

import com.example.batch.worker.core.domain.PipelineStepTemplate;
import com.example.batch.worker.processes.domain.ProcessJobContext;
import com.example.batch.worker.processes.domain.ProcessStageResult;
import java.util.List;

public interface ProcessStageExecutor {

  List<ProcessStageResult> execute(ProcessJobContext context);

  List<PipelineStepTemplate> defaultStepDefinitions();
}
