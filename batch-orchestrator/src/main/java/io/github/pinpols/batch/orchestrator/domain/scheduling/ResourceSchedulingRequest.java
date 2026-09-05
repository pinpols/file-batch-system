package io.github.pinpols.batch.orchestrator.domain.scheduling;

import java.time.Instant;
import lombok.Data;

@Data
public class ResourceSchedulingRequest {

  private String tenantId;
  private String jobCode;
  private String queueCode;
  private String workerGroup;
  private String workerType;
  private String windowCode;
  private Integer priority;
  private int requestedPartitionCount = 1;
  private Instant waitingSince;

  /**
   * 是否在本次判断中取得公平组的事务级准入锁。
   *
   * <p>新建任务会在同一事务内将实例推进到 RUNNING，必须保持默认 {@code true}。WAITING 队列的首轮判断只用于
   * 排序，真正派发前会在独立事务中再次校验，因此可以设为 {@code false}，避免一批候选为同一公平组反复争抢
   * advisory lock。
   */
  private boolean enforceFairShareAdmission = true;
}
