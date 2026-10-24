package io.github.pinpols.batch.worker.processes.cleanup;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 子 Job 启动会在 REQUIRES_NEW 事务中把其 Job Instance 关联到 workflow_node_run。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProcessStagingGenerationCandidate {

  private String tenantId;
  private Long taskId;
  private String generationId;
  private String batchKey;
}
