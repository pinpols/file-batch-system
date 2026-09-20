package io.github.pinpols.batch.console.domain.file.application.contract.query;

import io.github.pinpols.batch.console.application.contract.query.PageQueryRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = false)
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
