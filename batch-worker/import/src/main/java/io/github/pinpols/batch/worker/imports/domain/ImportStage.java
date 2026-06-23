package io.github.pinpols.batch.worker.imports.domain;

public enum ImportStage {
  RECEIVE,
  PREPROCESS,
  PARSE,
  VALIDATE,
  LOAD,
  FEEDBACK
}
