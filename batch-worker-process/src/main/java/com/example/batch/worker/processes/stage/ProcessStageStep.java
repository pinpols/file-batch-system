package com.example.batch.worker.processes.stage;

import com.example.batch.worker.processes.domain.ProcessJobContext;
import com.example.batch.worker.processes.domain.ProcessStage;
import com.example.batch.worker.processes.domain.ProcessStageResult;

public interface ProcessStageStep {

  ProcessStage stage();

  default String stepCode() {
    return "PROCESS_" + stage().name();
  }

  default String stepName() {
    return stepCode();
  }

  default String implCode() {
    return stepCode();
  }

  ProcessStageResult execute(ProcessJobContext context);
}
