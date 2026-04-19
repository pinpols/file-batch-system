package com.example.batch.orchestrator.domain.query;

import com.example.batch.common.model.PageRequest;

/**
 * JobTask 查询参数。5 字段 record；A-3.8 要求调用方走工厂方法而非显式传 null。
 *
 * <p>CLAUDE.md §Query Record 工厂方法规约：字段数 ≥ 5 且调用者仅传少数字段时，必须提供
 * 静态工厂，禁止在调用处写出 {@code null} 参数。
 */
public record JobTaskQuery(
    String tenantId,
    Long jobInstanceId,
    Long jobPartitionId,
    String taskStatus,
    PageRequest pageRequest) {

  /** 按租户全量查（列表页默认分页）。 */
  public static JobTaskQuery ofTenant(String tenantId, PageRequest pageRequest) {
    return new JobTaskQuery(tenantId, null, null, null, pageRequest);
  }

  /** 按 job_instance_id 查该实例下所有 task；pageRequest 可传 null 表示不分页。 */
  public static JobTaskQuery ofJobInstance(String tenantId, Long jobInstanceId) {
    return new JobTaskQuery(tenantId, jobInstanceId, null, null, null);
  }

  /** 按 job_instance_id + status 过滤。 */
  public static JobTaskQuery ofJobInstance(
      String tenantId, Long jobInstanceId, String taskStatus) {
    return new JobTaskQuery(tenantId, jobInstanceId, null, taskStatus, null);
  }

  /** 按 partition 查该分区下所有 task。 */
  public static JobTaskQuery ofPartition(String tenantId, Long jobPartitionId) {
    return new JobTaskQuery(tenantId, null, jobPartitionId, null, null);
  }
}
