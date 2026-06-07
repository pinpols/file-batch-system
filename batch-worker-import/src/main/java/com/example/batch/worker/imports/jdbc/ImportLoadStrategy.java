package com.example.batch.worker.imports.jdbc;

import com.example.batch.common.exception.WorkerConfigException;

/** jdbc_mapped_import 的落库策略。默认保持原 batch INSERT/UPSERT 行为。 */
public enum ImportLoadStrategy {
  BATCH_UPSERT,
  PARTITION_REPLACE_COPY;

  public static ImportLoadStrategy parse(Object raw) {
    if (raw == null) {
      return BATCH_UPSERT;
    }
    String text = String.valueOf(raw).trim();
    if (text.isEmpty()) {
      return BATCH_UPSERT;
    }
    String normalized = text.replace('-', '_').toUpperCase();
    for (ImportLoadStrategy strategy : values()) {
      if (strategy.name().equals(normalized)) {
        return strategy;
      }
    }
    throw new WorkerConfigException("unsupported jdbc_mapped_import.loadStrategy: " + text);
  }
}
