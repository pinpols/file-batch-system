package io.github.pinpols.batch.console.domain.file.query;

import io.github.pinpols.batch.common.model.PageRequest;
import java.time.Instant;

public record FileArrivalGroupQuery(
    String tenantId,
    String fileGroupCode,
    String arrivalState,
    Instant fromTime,
    Instant toTime,
    PageRequest pageRequest) {

  private FileArrivalGroupQuery(
      String tenantId, String fileGroupCode, String arrivalState, PageRequest pageRequest) {
    this(tenantId, fileGroupCode, arrivalState, null, null, pageRequest);
  }

  public static FileArrivalGroupQuery ofState(
      String tenantId, String fileGroupCode, String arrivalState, PageRequest pageRequest) {
    return new FileArrivalGroupQuery(tenantId, fileGroupCode, arrivalState, pageRequest);
  }
}
