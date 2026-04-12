package com.example.batch.worker.imports.domain;

import java.time.Instant;

public record ImportBadRecord(
    Long recordNo,
    String stageCode,
    String errorCode,
    String errorMessage,
    Object rawRecord,
    boolean skipped,
    String skipAction,
    Instant createdAt) {
  public ImportBadRecord {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }
}
