package com.example.batch.console.web.query;

import lombok.Data;

@Data
public class FileChainQueryRequest extends PageQueryRequest {

  private String tenantId;
  private String pipelineType;
  private String bizType;
  private String fileStatus;
  private String traceId;
  private String fileName;
  private String fileId;
  private String fromTime;
  private String toTime;
}
