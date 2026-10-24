package io.github.pinpols.batch.worker.processes.stage;

/** Persists or reads the generation authorized for the current PROCESS task claim. */
public interface ProcessStagingGenerationCoordinator {

  String startComputeGeneration(
      String tenantId,
      Long taskId,
      Long jobInstanceId,
      Long jobPartitionId,
      String workerId,
      String invocationId,
      String batchKey);

  String currentGeneration(
      String tenantId,
      Long taskId,
      Long jobInstanceId,
      Long jobPartitionId,
      String workerId,
      String invocationId);
}
