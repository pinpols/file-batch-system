package com.example.batch.orchestrator.domain.entity;

import com.example.batch.orchestrator.domain.value.JsonbString;
import java.time.Instant;

/**
 * worker_registry 表的不可变快照（MyBatis 通过 {@code resultMap+constructor} 映射）。
 *
 * <p>{@code capability_tags} 是 PG JSONB 列；mapper xml 通过 {@code capability_tags::text as
 * capability_tags_text} 转字符串走 {@code JsonbStringTypeHandler} 包装为 {@link JsonbString}。
 *
 * <p><b>不要加 Spring Data 注解</b>（{@code @Table @Id @Column}）—— 本表已迁 MyBatis 后由 {@code
 * WorkerRegistryMapper} 接管 CRUD；保留 SDJ 注解会被框架误扫成 Repository。
 *
 * <p>V87 (2026-05-03): 加 {@code maxConcurrent} 反压字段，{@code DefaultWorkerSelector} 在 {@code
 * current_load >= max_concurrent} 时 skip 该 worker。
 */
public record WorkerRegistryEntity(
    Long id,
    String tenantId,
    String workerCode,
    String workerGroup,
    JsonbString capabilityTags,
    String resourceTag,
    String status,
    Instant heartbeatAt,
    Integer currentLoad,
    Integer maxConcurrent,
    Instant drainStartedAt,
    Instant drainDeadlineAt) {
  /** 心跳更新：状态、心跳时间、负载、能力标签。 */
  public WorkerRegistryEntity withHeartbeat(
      String status, Instant heartbeatAt, Integer currentLoad, JsonbString capabilityTags) {
    return new WorkerRegistryEntity(
        id,
        tenantId,
        workerCode,
        workerGroup,
        capabilityTags,
        resourceTag,
        status,
        heartbeatAt,
        currentLoad,
        maxConcurrent,
        drainStartedAt,
        drainDeadlineAt);
  }

  /** 仅更新状态（如 OFFLINE）。 */
  public WorkerRegistryEntity withStatus(String status, Instant heartbeatAt) {
    return new WorkerRegistryEntity(
        id,
        tenantId,
        workerCode,
        workerGroup,
        capabilityTags,
        resourceTag,
        status,
        heartbeatAt,
        currentLoad,
        maxConcurrent,
        drainStartedAt,
        drainDeadlineAt);
  }

  /** 开始排空：设置 DRAINING 状态和排空窗口。 */
  public WorkerRegistryEntity withDrain(
      String status, Instant drainStartedAt, Instant drainDeadlineAt, Instant heartbeatAt) {
    return new WorkerRegistryEntity(
        id,
        tenantId,
        workerCode,
        workerGroup,
        capabilityTags,
        resourceTag,
        status,
        heartbeatAt,
        currentLoad,
        maxConcurrent,
        drainStartedAt,
        drainDeadlineAt);
  }

  /** 标记已下线：清除排空时间戳。 */
  public WorkerRegistryEntity withDecommissioned(Instant heartbeatAt) {
    return new WorkerRegistryEntity(
        id,
        tenantId,
        workerCode,
        workerGroup,
        capabilityTags,
        resourceTag,
        "DECOMMISSIONED",
        heartbeatAt,
        currentLoad,
        maxConcurrent,
        null,
        null);
  }
}
