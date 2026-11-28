package io.github.pinpols.batch.worker.processes.stage;

import java.util.function.Supplier;

import io.github.pinpols.batch.worker.processes.domain.ProcessJobContext;
import io.github.pinpols.batch.worker.processes.domain.ProcessStage;
import io.github.pinpols.batch.worker.processes.domain.ProcessStageResult;

/** 仅在持久化任务调用仍为当前尝试时执行 PROCESS 阶段。 */
@FunctionalInterface
public interface ProcessInvocationFence {

    ProcessStageResult execute(
            ProcessJobContext context, ProcessStage stage, Supplier<ProcessStageResult> stageExecution);
}
