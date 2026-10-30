package io.github.pinpols.batch.orchestrator.domain.scheduling;

import java.time.Instant;
import java.util.List;
import lombok.Data;

@Data
public class ResourceSchedulingRequest {

  private String tenantId;
  private String jobCode;
  private String queueCode;
  private String workerGroup;
  private String workerType;
  private String requiredCapability;
  private String resourceProfile;
  private String downstreamChannelCode;
  /** 同一次派发涉及的全部下游渠道；束分发和动态 fan-out 可同时包含多个目标。 */
  private List<String> downstreamChannelCodes = List.of();

  private String windowCode;
  private Integer priority;
  private int requestedPartitionCount = 1;
  private Instant waitingSince;

  /**
   * 本次调度是否会把一个新的 {@code job_instance} 推进为活跃态。初始 launch 保持 {@code true}；
   * 父实例仍在 WAITING 时的首次释放保持 {@code true}；父实例已活跃的后续分区或 DAG 节点设为
   * {@code false}，避免重复占用全局、租户、队列及公平共享组的 Job 配额。
   */
  private boolean newJobAdmission = true;
}
