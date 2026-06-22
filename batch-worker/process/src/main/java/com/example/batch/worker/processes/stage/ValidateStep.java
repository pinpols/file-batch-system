package com.example.batch.worker.processes.stage;

import com.example.batch.worker.processes.domain.ProcessJobContext;
import com.example.batch.worker.processes.domain.ProcessStage;
import com.example.batch.worker.processes.domain.ProcessStageResult;
import org.springframework.stereotype.Component;

/** PROCESS VALIDATE 阶段:在 staging 上跑数据质量规则,委托到 plugin.validate()。 */
@Component
public class ValidateStep implements ProcessStageStep {

  @Override
  public ProcessStage stage() {
    return ProcessStage.VALIDATE;
  }

  @Override
  public ProcessStageResult execute(ProcessJobContext context) {
    ProcessComputePlugin plugin = context.getResolvedPlugin();
    if (plugin == null) {
      return ProcessStageResult.success(stage());
    }
    ProcessStageResult result = plugin.validate(context);
    return result == null ? ProcessStageResult.success(stage()) : result;
  }
}
