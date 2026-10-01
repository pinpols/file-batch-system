package io.github.pinpols.batch.console.domain.file.query;

import io.github.pinpols.batch.common.model.PageRequest;
import java.time.Instant;

public record FileDispatchRecordQuery(
    String tenantId,
    String keyword,
    Long fileId,
    String channelCode,
    String dispatchStatus,
    String receiptStatus,
    Instant fromTime,
    Instant toTime,
    PageRequest pageRequest) {

  public FileDispatchRecordQuery withoutPage() {
    return new FileDispatchRecordQuery(
        tenantId,
        keyword,
        fileId,
        channelCode,
        dispatchStatus,
        receiptStatus,
        fromTime,
        toTime,
        null);
  }
}
