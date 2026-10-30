package io.github.pinpols.batch.console.domain.job.view;

import java.time.Instant;
import lombok.Data;

/** 作业进度只读取运行标识、计数和冻结的流水线版本，不读取可变定义或业务载荷。 */
@Data
public class JobExecutionView {
  private Long id;
  private String tenantId;
  private String jobCode;
  private String instanceStatus;
  private Instant startedAt;
  private Instant finishedAt;
  private Integer expectedPartitions;
  private Long workflowRunId;
  private String nodeCode;
  private Long pipelineInstanceId;
  private String pipelineStatus;
  private String currentStage;
  private String systemPipelineCode;
  private Integer systemPipelineVersion;
}
