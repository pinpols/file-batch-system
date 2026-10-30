package io.github.pinpols.batch.console.application.contract.query;

import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class FileChainQueryRequest extends PageQueryRequest {

  private String tenantId;
  private String pipelineType;
  private String bizType;
  private String fileStatus;
  private String traceId;
  private String fileName;
  private String fileId;

  @Size(max = 128, message = "jobCode too long (max 128)")
  private String jobCode;

  @Size(max = 10, message = "startDate too long (max 10)")
  private String startDate;

  @Size(max = 10, message = "endDate too long (max 10)")
  private String endDate;

  private Boolean arrivalToday;
  private String fromTime;
  private String toTime;
}
