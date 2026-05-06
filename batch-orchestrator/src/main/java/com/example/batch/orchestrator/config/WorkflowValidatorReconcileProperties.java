package com.example.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** ADR-025 §决策 §定期对账 — workflow 静态校验 reconciler 配置。 */
@Data
@ConfigurationProperties(prefix = "batch.workflow.validator")
public class WorkflowValidatorReconcileProperties {

  private boolean enabled = true;

  /** 默认半夜跑一次。 */
  private long pollIntervalMillis = 86_400_000L;

  /** 单轮处理 enabled workflow 上限。 */
  private int batchSize = 500;
}
