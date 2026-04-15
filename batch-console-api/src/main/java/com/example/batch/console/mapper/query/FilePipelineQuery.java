package com.example.batch.console.mapper.query;

import com.example.batch.common.model.PageRequest;
import java.time.Instant;

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

  public FilePipelineQuery withoutPage() {
    return new FilePipelineQuery(
        tenantId,
        fileId,
        pipelineInstanceId,
        pipelineType,
        runStatus,
        traceId,
        fromTime,
        toTime,
        null);
  }
}
