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
}
