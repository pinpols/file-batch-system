package com.example.batch.orchestrator.service;

import com.example.batch.common.dto.WorkerHeartbeatDto;
import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryRecord;
import com.example.batch.orchestrator.domain.value.JsonbString;
import com.example.batch.orchestrator.mapper.TouchHeartbeatParam;
import com.example.batch.orchestrator.mapper.WorkerRegistryMapper;
import com.example.batch.orchestrator.repository.WorkerRegistryRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service("orchestratorWorkerRegistryService")
@RequiredArgsConstructor
public class DefaultWorkerRegistryService implements WorkerRegistryServerService {

  private final WorkerRegistryRepository workerRegistryRepository;
  private final WorkerRegistryMapper workerRegistryMapper;

  @Override
  @Transactional
  public WorkerRegistryRecord register(WorkerHeartbeatDto request) {
    WorkerRegistryRecord registry =
        workerRegistryRepository.findFirstByTenantIdAndWorkerCode(
            request.tenantId(), request.workerCode());
    String newStatus =
        resolveIncomingStatus(
            request,
            WorkerRegistryStatus.ONLINE.code(),
            registry == null ? null : registry.status());
    Instant heartbeatAt = firstHeartbeat(request);
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
          new WorkerRegistryRecord(
              null,
              request.tenantId(),
              request.workerCode(),
              request.workerGroup(),
              newTags,
              null,
              newStatus,
              heartbeatAt,
              newLoad,
              null,
              null);
    } else {
      registry = registry.withHeartbeat(newStatus, heartbeatAt, newLoad, newTags);
    }
    return workerRegistryRepository.save(registry);
  }

  @Override
  @Transactional
  public WorkerRegistryRecord heartbeat(String workerCode, WorkerHeartbeatDto request) {
    if (request == null) {
      return null;
    }
    WorkerRegistryRecord registry =
        workerRegistryRepository.findFirstByTenantIdAndWorkerCode(request.tenantId(), workerCode);
    if (registry == null) {
      return register(request);
    }
    String newStatus = resolveHeartbeatStatus(request, registry.status());
    Instant heartbeatAt = firstHeartbeat(request);
    Integer newLoad =
        request.currentLoad() != null ? request.currentLoad() : registry.currentLoad();
    JsonbString newTags =
        request.capabilityTags() != null
            ? JsonbString.of(JsonUtils.toJson(request.capabilityTags()))
            : registry.capabilityTags();
    workerRegistryMapper.touchHeartbeat(
        TouchHeartbeatParam.builder()
            .tenantId(request.tenantId())
            .workerCode(workerCode)
            .nextStatus(newStatus)
            .heartbeatAt(heartbeatAt)
            .currentLoad(newLoad)
            .capabilityTags(newTags == null ? null : newTags.getValue())
            .build());
    return workerRegistryRepository.findFirstByTenantIdAndWorkerCode(
        request.tenantId(), workerCode);
  }

  @Override
  @Transactional
  public void deactivate(String tenantId, String workerCode) {
    updateStatus(tenantId, workerCode, WorkerRegistryStatus.OFFLINE.code());
  }

  @Override
  @Transactional
  public WorkerRegistryRecord updateStatus(String tenantId, String workerCode, String status) {
    WorkerRegistryRecord registry =
        workerRegistryRepository.findFirstByTenantIdAndWorkerCode(tenantId, workerCode);
    if (registry == null) {
      return null;
    }
    String newStatus = resolveIncomingStatus(null, status, registry.status());
    registry = registry.withStatus(newStatus, Instant.now());
    return workerRegistryRepository.save(registry);
  }

  private Instant firstHeartbeat(WorkerHeartbeatDto request) {
    return request.heartbeatAt() == null ? Instant.now() : request.heartbeatAt();
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
