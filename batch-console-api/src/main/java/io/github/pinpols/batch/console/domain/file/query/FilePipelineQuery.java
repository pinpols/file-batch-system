package io.github.pinpols.batch.console.domain.file.query;

import lombok.Builder;

import io.github.pinpols.batch.common.model.PageRequest;
import java.time.Instant;
@Builder(toBuilder = true)
public record FilePipelineQuery(
    String tenantId,
    String keyword,
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
