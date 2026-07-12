package io.github.pinpols.batch.console.domain.job.query;

import io.github.pinpols.batch.common.model.PageRequest;

public record JobPartitionQuery(
    String tenantId,
    Long jobInstanceId,
    String partitionStatus,
    PageRequest pageRequest,
    Long cursorId) {

  /** 仅按 (tenantId, jobInstanceId) 取全部分区的最小查询;状态/分页字段留空。 */
  public static JobPartitionQuery forJobInstance(String tenantId, Long jobInstanceId) {
    return new JobPartitionQuery(tenantId, jobInstanceId, null, null, null);
  }
}
