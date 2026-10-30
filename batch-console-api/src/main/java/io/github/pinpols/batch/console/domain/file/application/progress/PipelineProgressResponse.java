package io.github.pinpols.batch.console.domain.file.application.progress;

import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFilePipelineStepProgressResponse;
import java.time.Instant;
import java.util.List;

/** 当前订阅范围的完整快照；steps仅含每个阶段的最新尝试。 */
public record PipelineProgressResponse(
    String tenantId, Instant generatedAt, List<Pipeline> pipelines) {
  public record Pipeline(
      Long pipelineInstanceId,
      Long fileId,
      String fileName,
      String runStatus,
      String currentStage,
      String systemPipelineCode,
      Integer systemPipelineVersion,
      boolean stageGraphAvailable,
      List<String> stageCodes,
      List<ConsoleFilePipelineStepProgressResponse> steps) {}
}
