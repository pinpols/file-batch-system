package io.github.pinpols.batch.worker.processes.stage;

import io.github.pinpols.batch.common.service.DryRunGuard;
import io.github.pinpols.batch.worker.processes.domain.ProcessJobContext;
import io.github.pinpols.batch.worker.processes.domain.ProcessStage;
import io.github.pinpols.batch.worker.processes.domain.ProcessStageResult;
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
    // ADR-026: 演练模式不写业务表（PROCESS commit 是最大副作用入口），直接 success 返回。
    if (DryRunGuard.fromAttributes(context == null ? null : context.getAttributes()).isDryRun()) {
      return ProcessStageResult.success(stage());
    }
    ProcessComputePlugin plugin = context.getResolvedPlugin();
    if (plugin == null) {
      return ProcessStageResult.success(stage());
    }
    ProcessStageResult result = plugin.commit(context);
    return result == null ? ProcessStageResult.success(stage()) : result;
  }
}
