package io.github.pinpols.batch.worker.processes.domain;

public enum ProcessStage {
  PREPARE,
  COMPUTE,
  VALIDATE,
  COMMIT,
  FEEDBACK
}
