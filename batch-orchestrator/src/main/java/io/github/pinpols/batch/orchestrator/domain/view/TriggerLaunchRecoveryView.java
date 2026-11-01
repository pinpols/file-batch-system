package io.github.pinpols.batch.orchestrator.domain.view;

import lombok.Data;

/** Kafka 启动意图恢复的查询视图，供周期重投。 */
@Data
public class TriggerLaunchRecoveryView {

  private String tenantId;
  private String requestId;
  private String envelopeCiphertext;
  private String traceId;
  private Integer attempts;
  private Long recoveryGeneration;
  private String recoveryStatus;
  private String lastErrorCode;
}
