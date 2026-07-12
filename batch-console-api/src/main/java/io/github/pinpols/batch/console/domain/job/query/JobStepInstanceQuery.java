package io.github.pinpols.batch.console.domain.job.query;

import io.github.pinpols.batch.common.model.PageRequest;

public record JobStepInstanceQuery(
    String tenantId,
    Long jobInstanceId,
    Long jobPartitionId,
    String stepCode,
    String stepStatus,
    PageRequest pageRequest,
    Long cursorId) {

  /** 仅按 (tenantId, jobInstanceId) 取全部 step 的最小查询;其余过滤/分页字段留空。 */
  public static JobStepInstanceQuery forJobInstance(String tenantId, Long jobInstanceId) {
    return new JobStepInstanceQuery(tenantId, jobInstanceId, null, null, null, null, null);
  }
}
