package io.github.pinpols.batch.console.domain.job.web.request;

import io.github.pinpols.batch.common.validation.ValidBizDate;
import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CompensationCommandRequest {

  @ValidTenantId private String tenantId;

  @NotBlank
  @Size(max = 64, message = "compensationType too long (max 64)")
  private String compensationType;

  private Long targetId;
  private String targetInstanceNo;

  @Size(max = 128, message = "jobCode too long (max 128)")
  private String jobCode;

  @ValidBizDate private String bizDate;
  private String batchNo;
  private Long relatedFileId;
  private String channelCode;
  private String reason;
  private String operatorId;
  private String approvalId;
  private String strategy;
}
