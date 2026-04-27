package com.example.batch.worker.processes.stage;

import com.example.batch.worker.processes.domain.ProcessJobContext;
import com.example.batch.worker.processes.domain.ProcessStage;
import com.example.batch.worker.processes.domain.ProcessStageResult;
import org.springframework.stereotype.Component;

/** PROCESS PREPARE 阶段:Pre-flight 校验,委托到 plugin.prepare()。 */
@Component
public class PrepareStep implements ProcessStageStep {

  @Override
  public ProcessStage stage() {
    return ProcessStage.PREPARE;
  }

  @Override
  public ProcessStageResult execute(ProcessJobContext context) {
    ProcessComputePlugin plugin = context.getResolvedPlugin();
    if (plugin == null) {
      return ProcessStageResult.success(stage());
    }
    plugin.prepare(context);
    return ProcessStageResult.success(stage());
  }
}
