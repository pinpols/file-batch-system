package io.github.pinpols.batch.orchestrator.application.service.workflow;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Workflow 根启动容量等待的有界恢复参数：每轮认领数量、并发启动数、立即补槽批量与持久化等待的下次检查间隔。 */
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

  /**
   * 每轮并发启动数。恢复的每条等待都要跑一次完整 launch 事务，串行执行时单租户吞吐被单线程限制；
   * 并发度只提高同一轮内不同 requestId 的并行度，同一 requestId 仍由等待行租约互斥。
   */
  @Min(1)
  private int parallelism = 4;

  /**
   * 终态提交后立即补槽的单次上限。补槽在完成路径上触发，处理整批 {@code batchSize} 会长时间占住
   * 完成线程并反向拖慢后续完成；剩余等待由定时扫描继续消化。
   */
  @Min(1)
  private int resumeBatchSize = 8;
}
