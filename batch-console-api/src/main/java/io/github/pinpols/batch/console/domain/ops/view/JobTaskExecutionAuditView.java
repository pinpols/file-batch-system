package io.github.pinpols.batch.console.domain.ops.view;

import java.time.Instant;
import lombok.Data;

/**
 * console-api 只读投影：{@code batch.job_task} 的执行审计子集。
 *
 * <p>{@code effectiveParameters}、{@code resultSummary} 为 JSONB，mapper 端 {@code ::text} 读出，service 解析并脱敏后返回。
 * 只读，不回写 job_task（状态主机是 orchestrator）。
 */
@Data
public class JobTaskExecutionAuditView {

  private Long id;
  private Long jobInstanceId;
  private String jobCode;
  private String taskStatus;
  private String assignedWorkerCode;
  private String effectiveParameters;
  private String resultSummary;
  private String errorCode;
  private String errorMessage;
  private Instant startedAt;
  private Instant finishedAt;
}
