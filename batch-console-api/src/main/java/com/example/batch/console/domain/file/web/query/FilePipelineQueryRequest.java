package com.example.batch.console.domain.file.web.query;

import com.example.batch.console.web.query.PageQueryRequest;
import lombok.Data;

@Data
public class FilePipelineQueryRequest extends PageQueryRequest {

  private String tenantId;
  private Long fileId;
  private Long pipelineInstanceId;
  private String pipelineType;
  private String runStatus;
  private String traceId;
  private String fromTime;
  private String toTime;
}
