package io.github.pinpols.batch.console.domain.file.query;

import io.github.pinpols.batch.common.model.PageRequest;

public record FileErrorRecordQuery(
    String tenantId,
    Long fileId,
    String errorStage,
    String errorCode,
    Boolean skipped,
    PageRequest pageRequest) {

  public static FileErrorRecordQuery ofFile(String tenantId, Long fileId, PageRequest pageRequest) {
    return new FileErrorRecordQuery(tenantId, fileId, null, null, null, pageRequest);
  }

  public static FileErrorRecordQuery ofFileAndStage(
      String tenantId, Long fileId, String errorStage) {
    return new FileErrorRecordQuery(tenantId, fileId, errorStage, null, null, null);
  }
}
