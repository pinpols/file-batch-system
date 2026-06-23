package io.github.pinpols.batch.console.domain.file.query;

import io.github.pinpols.batch.common.model.PageRequest;
import java.time.Instant;
import lombok.Builder;

@Builder(toBuilder = true)
public record FilePipelineQuery(
    String tenantId,
    Long fileId,
    Long pipelineInstanceId,
    String pipelineType,
    String runStatus,
    String traceId,
    Instant fromTime,
    Instant toTime,
    PageRequest pageRequest) {

  public static FilePipelineQuery ofPipeline(
      String tenantId, Long pipelineInstanceId, PageRequest pageRequest) {
    return builder()
        .tenantId(tenantId)
        .pipelineInstanceId(pipelineInstanceId)
        .pageRequest(pageRequest)
        .build();
  }

  public FilePipelineQuery withoutPage() {
    return toBuilder().pageRequest(null).build();
  }
}
