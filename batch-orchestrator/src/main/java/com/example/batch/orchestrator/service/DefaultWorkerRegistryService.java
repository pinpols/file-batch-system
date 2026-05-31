package com.example.batch.orchestrator.service;

import com.example.batch.common.dto.WorkerHeartbeatDto;
import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import com.example.batch.orchestrator.domain.param.TouchHeartbeatParam;
import com.example.batch.orchestrator.domain.value.JsonbString;
import com.example.batch.orchestrator.mapper.WorkerRegistryMapper;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Worker 注册表服务端入口：register（新建/重连）/ heartbeat（续活）/ updateStatus / deactivate。
 *
 * <p>关键不变量：
 *
 * <ul>
 *   <li><b>DRAINING / DECOMMISSIONED 状态不可被心跳重置为 ONLINE</b>——见 {@link #resolveHeartbeatStatus}。 否则
 *       {@link
 *       com.example.batch.orchestrator.application.service.DefaultWorkerDrainGovernanceService}
 *       正在执行的 drain / decommission 会被 worker 端的周期心跳悄悄回滚。
 *   <li><b>heartbeat 未注册时自动降级到 register</b>——兜底首次 register 请求丢失的竞态，worker 不会因为 register 漏发就永远心跳无主。
 *   <li><b>幂等 upsert</b>：register 对已存在记录走 {@code withHeartbeat} 更新而不是报错，允许 worker 重启后 重新 register
 *       同一 workerCode。
 * </ul>
 */
@Service("orchestratorWorkerRegistryService")
@RequiredArgsConstructor
public class DefaultWorkerRegistryService implements WorkerRegistryServerService {

  private final WorkerRegistryMapper workerRegistryMapper;

  @Lazy @Autowired private DefaultWorkerRegistryService self;

  @Override
  @Transactional
  public WorkerRegistryEntity register(WorkerHeartbeatDto request) {
    WorkerRegistryEntity registry =
        workerRegistryMapper.selectByTenantAndWorkerCode(request.tenantId(), request.workerCode());
    String newStatus =
        resolveIncomingStatus(
            request,
            WorkerRegistryStatus.ONLINE.code(),
            registry == null ? null : registry.status());
    Instant heartbeatAt = firstHeartbeat();
    Integer newLoad =
        request.currentLoad() != null
            ? request.currentLoad()
            : (registry == null ? 0 : registry.currentLoad());
    JsonbString newTags =
        request.capabilityTags() != null
            ? JsonbString.of(JsonUtils.toJson(request.capabilityTags()))
            : (registry == null ? null : registry.capabilityTags());

    if (registry == null) {
      registry =
          new WorkerRegistryEntity(
              null,
              request.tenantId(),
              request.workerCode(),
              request.workerGroup(),
              newTags,
              null,
              newStatus,
              heartbeatAt,
              newLoad,
              null, // maxConcurrent: 走 DB DEFAULT 10 (V87)
              null,
              null);
    } else {
      registry = registry.withHeartbeat(newStatus, heartbeatAt, newLoad, newTags);
    }
    WorkerRegistryEntity saved = persist(registry);
    // ADR-035 §2:SDK 自托管 worker 通过 workerGroup="sdk-self-hosted" 识别,标到列上让
    // console "我的 Worker" 页过滤。幂等。
    if ("sdk-self-hosted".equals(request.workerGroup())) {
      workerRegistryMapper.markSelfHosted(request.tenantId(), request.workerCode());
    }
    return saved;
  }

  @Override
  @Transactional
  public WorkerRegistryEntity heartbeat(String workerCode, WorkerHeartbeatDto request) {
    if (request == null) {
      return null;
    }
    WorkerRegistryEntity registry =
        workerRegistryMapper.selectByTenantAndWorkerCode(request.tenantId(), workerCode);
    if (registry == null) {
      return self.register(request);
    }
    String newStatus = resolveHeartbeatStatus(request, registry.status());
    Integer newLoad =
        request.currentLoad() != null ? request.currentLoad() : registry.currentLoad();
    JsonbString newTags =
        request.capabilityTags() != null
            ? JsonbString.of(JsonUtils.toJson(request.capabilityTags()))
            : registry.capabilityTags();
    // heartbeat_at 由 mapper xml 直接写为 DB current_timestamp（消除 worker 时钟漂移）。
    workerRegistryMapper.touchHeartbeat(
        TouchHeartbeatParam.builder()
            .tenantId(request.tenantId())
            .workerCode(workerCode)
            .nextStatus(newStatus)
            .currentLoad(newLoad)
            .capabilityTags(newTags == null ? null : newTags.getValue())
            .build());
    return workerRegistryMapper.selectByTenantAndWorkerCode(request.tenantId(), workerCode);
  }

  @Override
  @Transactional
  public void deactivate(String tenantId, String workerCode) {
    self.updateStatus(tenantId, workerCode, WorkerRegistryStatus.OFFLINE.code());
  }

  @Override
  @Transactional
  public WorkerRegistryEntity updateStatus(String tenantId, String workerCode, String status) {
    WorkerRegistryEntity registry =
        workerRegistryMapper.selectByTenantAndWorkerCode(tenantId, workerCode);
    if (registry == null) {
      return null;
    }
    String newStatus = resolveIncomingStatus(null, status, registry.status());
    registry = registry.withStatus(newStatus, BatchDateTimeSupport.utcNow());
    return persist(registry);
  }

  /**
   * 首次注册 / register 路径的 heartbeat_at。Spring Data JDBC 路径必须 Java 端持有时间，无法直接用 SQL current_timestamp；
   * 这里统一用 orchestrator JVM 时钟，<b>忽略 worker 提供的 {@code request.heartbeatAt()}</b>，避免 worker NTP
   * 漂移直接进入 DB 的 heartbeat_at 列。心跳路径走 mybatis xml 直接 current_timestamp，二者口径一致。
   */
  private Instant firstHeartbeat() {
    return BatchDateTimeSupport.utcNow();
  }

  /**
   * MyBatis 替代原 Spring Data JDBC {@code repository.save}：id==null 走 insert（带 ON CONFLICT DO NOTHING
   * 防 UV 并发）；否则按 id 全字段 updateById。返回最新 DB 行（重新 selectByTenantAndWorkerCode 拿到带 id 的快照）。
   */
  private WorkerRegistryEntity persist(WorkerRegistryEntity registry) {
    if (registry.id() == null) {
      workerRegistryMapper.insert(registry);
    } else {
      workerRegistryMapper.updateById(registry);
    }
    return workerRegistryMapper.selectByTenantAndWorkerCode(
        registry.tenantId(), registry.workerCode());
  }

  private String resolveHeartbeatStatus(WorkerHeartbeatDto request, String currentStatus) {
    if (WorkerRegistryStatus.DECOMMISSIONED.code().equals(currentStatus)) {
      return currentStatus;
    }
    if (WorkerRegistryStatus.DRAINING.code().equals(currentStatus)) {
      return currentStatus;
    }
    return resolveIncomingStatus(request, WorkerRegistryStatus.ONLINE.code(), currentStatus);
  }

  private String resolveIncomingStatus(
      WorkerHeartbeatDto request, String defaultStatus, String currentStatus) {
    String requestedStatus = request == null ? null : request.status();
    if (requestedStatus == null || requestedStatus.isBlank()) {
      return defaultStatus == null || defaultStatus.isBlank() ? currentStatus : defaultStatus;
    }
    return requestedStatus;
  }
}
