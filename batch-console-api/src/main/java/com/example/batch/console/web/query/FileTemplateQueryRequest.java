package com.example.batch.console.web.query;

import lombok.Data;

@Data
public class FileTemplateQueryRequest extends PageQueryRequest {

  private String tenantId;
  private String keyword;
  private String templateCode;
  private String templateName;
  private String templateType;
  private String bizType;
  private Boolean enabled = true;
}
