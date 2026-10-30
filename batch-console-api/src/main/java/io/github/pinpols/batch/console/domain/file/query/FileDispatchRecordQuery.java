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

  /** 按租户与文件查询分发记录，其余筛选和分页均不启用。 */
  public static FileDispatchRecordQuery ofFile(String tenantId, Long fileId) {
    return new FileDispatchRecordQuery(tenantId, null, fileId, null, null, null, null, null, null);
  }

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
