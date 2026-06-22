package com.example.batch.console.domain.file.web.query;

import com.example.batch.console.web.query.PageQueryRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = false)
@Data
public class FileChannelQueryRequest extends PageQueryRequest {

  private String tenantId;
  private String channelCode;
  private String channelType;
  private Boolean enabled = true;
}
