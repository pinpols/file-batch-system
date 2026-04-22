package com.example.batch.worker.core.domain;

import java.time.OffsetDateTime;
import java.util.List;
import lombok.Data;

@Data
public class WorkerRegistration {

  /**
   * 注册中心侧的 worker 标识。
   *
   * <p>将此值作为心跳和租约归属的稳定运行时实例键。
   */
  private String workerId;

  private String tenantId;
  private String workerType;

  /** Orchestrator 调度 / 消费者分组键。 */
  private String workerGroup;

  private String status;
  private String host;
  private Integer port;
  private Boolean active;
  private OffsetDateTime registeredAt;
  private OffsetDateTime lastHeartbeatAt;

  /** 进行中的任务数 / 已认领工作量；用于 Orchestrator worker 选择（值越低越优先）。 */
  private Integer currentLoad;

  /** 能力标签；心跳上报后写入 {@code worker_registry.capability_tags} JSONB，参与 selector 路由匹配。 */
  private List<String> capabilityTags;
}
