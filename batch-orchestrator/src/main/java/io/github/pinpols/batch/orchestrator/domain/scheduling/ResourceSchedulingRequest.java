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
   * 已有实例的 DAG 后续节点和 WAITING 重派必须设为 {@code false}，避免重复占用全局 job 配额。
   */
  private boolean newJobAdmission = true;

  /**
   * 是否在本次判断中取得公平组的事务级准入锁。
   *
   * <p>新建任务会在同一事务内将实例推进到 RUNNING，必须保持默认 {@code true}。WAITING 队列的首轮判断只用于
   * 排序，真正派发前会在独立事务中再次校验，因此可以设为 {@code false}，避免一批候选为同一公平组反复争抢
   * advisory lock。
   */
  private boolean enforceFairShareAdmission = true;
}
