package com.example.batch.orchestrator.domain.query;

import com.example.batch.common.model.PageRequest;

public record JobExecutionLogQuery(
    String tenantId,
    Long jobInstanceId,
    Long jobPartitionId,
    String logType,
    PageRequest pageRequest) {

  public static JobExecutionLogQuery ofPartition(
      String tenantId, Long jobInstanceId, Long jobPartitionId) {
    return new JobExecutionLogQuery(tenantId, jobInstanceId, jobPartitionId, null, null);
  }
}
