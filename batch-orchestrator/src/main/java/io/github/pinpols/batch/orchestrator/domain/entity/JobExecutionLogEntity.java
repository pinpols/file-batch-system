package io.github.pinpols.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class JobExecutionLogEntity {

  private Long id;
  private String tenantId;
  private Long jobInstanceId;
  private Long jobPartitionId;
  private String logLevel;
  private String logType;
  private String traceId;
  private String message;
  private String detailRef;
  private String extraJson;
  private Instant createdAt;
}
