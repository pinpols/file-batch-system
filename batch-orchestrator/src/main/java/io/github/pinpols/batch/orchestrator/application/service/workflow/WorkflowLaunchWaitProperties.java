package io.github.pinpols.batch.orchestrator.application.service.workflow;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Workflow 根启动容量等待的有界恢复参数：每轮认领数量和持久化等待的下次检查间隔。 */
@Data
@Validated
@ConfigurationProperties(prefix = "batch.workflow.launch-wait")
public class WorkflowLaunchWaitProperties {
  @Min(1)
  private int batchSize = 100;

  @Min(1)
  private long retryDelaySeconds = 5;

  /**
   * 认领等待行后的租约时长。扫描和“终态后立即补槽”可能并发，租约必须覆盖一次 launch 的排队与准入时间，
   * 否则同一请求会在上一轮尚未结束时被再次认领，形成行锁车队。
   */
  @Min(1)
  private long claimLeaseSeconds = 120;
}
