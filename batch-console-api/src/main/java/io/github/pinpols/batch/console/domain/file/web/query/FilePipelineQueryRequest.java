package io.github.pinpols.batch.console.domain.file.web.query;

import io.github.pinpols.batch.console.web.query.PageQueryRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = false)
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
