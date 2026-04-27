package com.example.batch.worker.processes.stage;

import com.example.batch.worker.processes.domain.ProcessJobContext;
import com.example.batch.worker.processes.domain.ProcessStage;
import com.example.batch.worker.processes.domain.ProcessStageResult;
import org.springframework.stereotype.Component;

/** PROCESS COMMIT 阶段:atomic publish staging → target,委托到 plugin.commit()。 */
@Component
public class CommitStep implements ProcessStageStep {

  @Override
  public ProcessStage stage() {
    return ProcessStage.COMMIT;
  }

  @Override
  public ProcessStageResult execute(ProcessJobContext context) {
    ProcessComputePlugin plugin = context.getResolvedPlugin();
    if (plugin == null) {
      return ProcessStageResult.success(stage());
    }
    ProcessStageResult result = plugin.commit(context);
    return result == null ? ProcessStageResult.success(stage()) : result;
  }
}
