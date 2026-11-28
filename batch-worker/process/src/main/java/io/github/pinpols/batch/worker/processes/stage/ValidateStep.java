package io.github.pinpols.batch.worker.processes.stage;

import org.springframework.stereotype.Component;

import io.github.pinpols.batch.common.service.DryRunGuard;
import io.github.pinpols.batch.worker.processes.domain.ProcessJobContext;
import io.github.pinpols.batch.worker.processes.domain.ProcessStage;
import io.github.pinpols.batch.worker.processes.domain.ProcessStageResult;

/** PROCESS VALIDATE 阶段:在 staging 上跑数据质量规则,委托到 plugin.validate()。 */
@Component
public class ValidateStep implements ProcessStageStep {

    @Override
    public ProcessStage stage() {
        return ProcessStage.VALIDATE;
    }

    @Override
    public ProcessStageResult execute(ProcessJobContext context) {
        if (DryRunGuard.fromAttributes(context == null ? null : context.getAttributes())
                .isDryRun()) {
            return ProcessStageResult.success(stage());
        }
        ProcessComputePlugin plugin = context.getResolvedPlugin();
        if (plugin == null) {
            return ProcessStageResult.success(stage());
        }
        ProcessStageResult result = plugin.validate(context);
        return result == null ? ProcessStageResult.success(stage()) : result;
    }
}
