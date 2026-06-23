package io.github.pinpols.batch.console.domain.file.web.query;

import io.github.pinpols.batch.console.web.query.PageQueryRequest;
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
