package io.github.pinpols.batch.worker.processes.stage;

import io.github.pinpols.batch.worker.processes.domain.ProcessInvocationIdentity;

/** 在当前调用身份下生成或复用暂存代次，避免跨 invocation 读取上一尝试的数据。 */
public interface ProcessStagingGenerationCoordinator {

  String startComputeGeneration(ProcessInvocationIdentity identity, String batchKey);

  String currentGeneration(ProcessInvocationIdentity identity);
}
