package io.github.pinpols.batch.worker.processes.stage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.worker.processes.domain.ProcessJobContext;
import io.github.pinpols.batch.worker.processes.domain.ProcessStage;
import io.github.pinpols.batch.worker.processes.domain.ProcessStageResult;
import org.springframework.stereotype.Component;

/** PROCESS PREPARE 阶段:Pre-flight 校验,委托到 plugin.prepare()。 */
@Component
public class PrepareStep implements ProcessStageStep {

  private static final ObjectMapper ERROR_OBJECT_MAPPER = new ObjectMapper();

  @Override
  public ProcessStage stage() {
    return ProcessStage.PREPARE;
  }

  @Override
  public ProcessStageResult execute(ProcessJobContext context) {
    // P2-5:DefaultProcessStageExecutor 解析时发现 step_definition.impl_code 显式配了某 plugin code
    // 但 plugin 注册表里找不到 → 这里 fail-fast 拒掉整个 task,避免 typo 静默 success。
    Object missing = context.getAttributes().get(ProcessRuntimeKeys.PROCESS_PLUGIN_NOT_FOUND);
    if (missing != null) {
      return ProcessStageResult.failure(
          stage(),
          "PROCESS_COMPUTE_PLUGIN_NOT_FOUND",
          "error.process.compute.plugin_not_found",
          new Object[] {missing},
          "process compute plugin not found: " + missing,
          ERROR_OBJECT_MAPPER);
    }
    ProcessComputePlugin plugin = context.getResolvedPlugin();
    if (plugin == null) {
      return ProcessStageResult.success(stage());
    }
    plugin.prepare(context);
    return ProcessStageResult.success(stage());
  }
}
