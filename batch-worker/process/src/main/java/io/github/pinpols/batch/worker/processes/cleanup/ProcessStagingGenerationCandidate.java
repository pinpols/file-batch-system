package io.github.pinpols.batch.worker.processes.cleanup;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 待核对的 PROCESS staging 代际身份，包含租户、任务、代际和批次键。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProcessStagingGenerationCandidate {

  private String tenantId;
  private Long taskId;
  private String generationId;
  private String batchKey;
  private Instant createdAt;

  public ProcessStagingGenerationCandidate(
      String tenantId, Long taskId, String generationId, String batchKey) {
    this(tenantId, taskId, generationId, batchKey, null);
  }
}
