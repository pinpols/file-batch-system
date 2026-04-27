package com.example.batch.worker.processes.stage;

import com.example.batch.worker.processes.domain.ProcessJobContext;
import com.example.batch.worker.processes.domain.ProcessStage;
import com.example.batch.worker.processes.domain.ProcessStageResult;
import org.springframework.stereotype.Component;

/** PROCESS COMPUTE 阶段:写 staging,委托到 plugin.compute()。 */
@Component
public class ComputeStep implements ProcessStageStep {

  /** Payload 中可选字段,允许业务通过 task params 临时指定 plugin 实现码(用于自定义插件按需 opt-in)。 */
  public static final String ATTR_PROCESS_IMPL_CODE = "processImplCode";

  @Override
  public ProcessStage stage() {
    return ProcessStage.COMPUTE;
  }

  @Override
  public ProcessStageResult execute(ProcessJobContext context) {
    ProcessComputePlugin plugin = context.getResolvedPlugin();
    if (plugin == null) {
      // 没有 plugin 配置时仍允许走通(便于开箱跑通骨架),但写一个 0 行的 processedCount 占位。
      context.getAttributes().putIfAbsent("processedCount", 0);
      return ProcessStageResult.success(stage());
    }
    ProcessStageResult result = plugin.compute(context);
    return result == null
        ? ProcessStageResult.failure(
            stage(), "PROCESS_COMPUTE_EMPTY_RESULT", "process compute returned null")
        : result;
  }
}
