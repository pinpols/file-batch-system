package io.github.pinpols.batch.console.domain.file.query;

import lombok.Builder;

import io.github.pinpols.batch.common.model.PageRequest;
@Builder(toBuilder = true)
public record FilePipelineStepRunQuery(
    String tenantId,
    String keyword,
    Long pipelineInstanceId,
    String stepCode,
    String stageCode,
    String stepStatus,
    PageRequest pageRequest) {

  public FilePipelineStepRunQuery withoutPage() {
    return toBuilder().pageRequest(null).build();
  }
}
