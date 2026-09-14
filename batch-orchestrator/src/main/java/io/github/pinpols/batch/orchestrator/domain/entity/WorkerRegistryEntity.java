package io.github.pinpols.batch.orchestrator.domain.entity;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.domain.value.JsonbString;
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
    Instant drainDeadlineAt,
    // SDK Phase 5 / SDK-P5-3 运行指纹（均可空，非 SDK 的文件 pipeline worker 不上报）。
    String hostName,
    String hostIp,
    String processId,
    String buildId,
    String sdkVersion,
    String workerPoolCode) {

  /** Worker 未声明容量时的平台默认并发；DDL 的同值 DEFAULT 仅作为数据库最后防线。 */
  public static final int DEFAULT_MAX_CONCURRENT = 10;

  /**
   * 兼容构造器：不带运行指纹（hostName/hostIp/processId/buildId/sdkVersion），全置 null。 缓存重建 / 选择器 /
   * 测试等不关心指纹的路径沿用此入口，避免大面积改 canonical 调用点。
   */
  @SuppressWarnings("PMD.ExcessiveParameterList")
  public WorkerRegistryEntity(
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
    this(
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
        drainDeadlineAt,
        null,
        null,
        null,
        null,
        null,
        workerCode);
  }

  /** 兼容 workerPoolCode 引入前的完整构造调用。 */
  @SuppressWarnings("PMD.ExcessiveParameterList")
  public WorkerRegistryEntity(
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
      Instant drainDeadlineAt,
      String hostName,
      String hostIp,
      String processId,
      String buildId,
      String sdkVersion) {
    this(
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
        drainDeadlineAt,
        hostName,
        hostIp,
        processId,
        buildId,
        sdkVersion,
        workerCode);
  }

  /** 任务路由使用稳定池代码；历史行没有该列时回退实例 workerCode。 */
  public String routingCode() {
    return EmptyChecks.isBlank(workerPoolCode) ? workerCode : workerPoolCode;
  }

  /** 注册请求可在不改变实例主键的情况下刷新稳定路由池代码。 */
  public WorkerRegistryEntity withWorkerPoolCode(String newWorkerPoolCode) {
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
        drainDeadlineAt,
        hostName,
        hostIp,
        processId,
        buildId,
        sdkVersion,
        newWorkerPoolCode);
  }

  /** 注册/重注册更新：状态、心跳时间、负载、能力标签和 worker 自报并发上限。 */
  public WorkerRegistryEntity withHeartbeat(
      String status,
      Instant heartbeatAt,
      Integer currentLoad,
      JsonbString capabilityTags,
      Integer maxConcurrent) {
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
        drainDeadlineAt,
        hostName,
        hostIp,
        processId,
        buildId,
        sdkVersion,
        workerPoolCode);
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
        drainDeadlineAt,
        hostName,
        hostIp,
        processId,
        buildId,
        sdkVersion,
        workerPoolCode);
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
        drainDeadlineAt,
        hostName,
        hostIp,
        processId,
        buildId,
        sdkVersion,
        workerPoolCode);
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
        null,
        hostName,
        hostIp,
        processId,
        buildId,
        sdkVersion,
        workerPoolCode);
  }

  /** SDK-P5-3：刷新运行指纹（register 路径，worker 重启可能换 host / 升 SDK 版本）。 */
  public WorkerRegistryEntity withFingerprint(
      String hostName, String hostIp, String processId, String buildId, String sdkVersion) {
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
        drainDeadlineAt,
        hostName,
        hostIp,
        processId,
        buildId,
        sdkVersion,
        workerPoolCode);
  }
}
