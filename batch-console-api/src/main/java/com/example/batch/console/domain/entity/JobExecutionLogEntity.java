package com.example.batch.console.domain.entity;

import java.time.Instant;
import lombok.Data;

/**
 * {@code batch.job_execution_log} 行类型(console 只读视角)。worker / orchestrator 执行过程写入,console 仅查询展示,见 V7
 * migration。
 */
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
