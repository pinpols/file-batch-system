package io.github.pinpols.batch.orchestrator.domain.entity;

import lombok.Data;

/** Kafka 消费重试耗尽后的启动意图（envelope 密文），供周期重投。 */
@Data
public class TriggerLaunchRecoveryEntity {

  private String tenantId;
  private String requestId;
  private String envelopeCiphertext;
  private String traceId;
  private Integer attempts;
  private Long recoveryGeneration;
  private String recoveryStatus;
  private String lastErrorCode;
}
