package io.github.pinpols.batch.orchestrator.service;

import io.github.pinpols.batch.common.dto.WorkerHeartbeatDto;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkerRegistryEntity;

/**
 * Worker 注册中心服务（服务端视角）。 处理 Worker 节点的首次注册、定期心跳上报、主动下线以及状态更新操作。 实现类须维护 Worker
 * 在线状态，并在心跳超时后将其标记为不可用，以保证路由层的准确性。
 */
public interface WorkerRegistryServerService {

  WorkerRegistryEntity register(WorkerHeartbeatDto request);

  WorkerRegistryEntity heartbeat(String workerCode, WorkerHeartbeatDto request);

  void deactivate(String tenantId, String workerCode);

  WorkerRegistryEntity updateStatus(String tenantId, String workerCode, String status);
}
