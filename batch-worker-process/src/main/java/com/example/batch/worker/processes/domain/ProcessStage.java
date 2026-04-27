package com.example.batch.worker.processes.domain;

public enum ProcessStage {
  PREPARE,
  COMPUTE,
  VALIDATE,
  COMMIT,
  FEEDBACK
}
