package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;
import java.time.Instant;

public record FileDispatchRecordQuery(
    String tenantId,
    Long fileId,
    String channelCode,
    String dispatchStatus,
    String receiptStatus,
    Instant fromTime,
    Instant toTime,
    PageRequest pageRequest) {

  public FileDispatchRecordQuery withoutPage() {
    return new FileDispatchRecordQuery(
        tenantId, fileId, channelCode, dispatchStatus, receiptStatus, fromTime, toTime, null);
  }
}
