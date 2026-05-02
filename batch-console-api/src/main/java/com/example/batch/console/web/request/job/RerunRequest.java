package com.example.batch.console.web.request.job;

import com.example.batch.common.validation.ValidBizDate;
import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RerunRequest {

  @ValidTenantId private String tenantId;

  @NotBlank
  @Size(max = 128, message = "jobCode too long (max 128)")
  private String jobCode;

  @NotBlank @ValidBizDate private String bizDate;
  private Long targetId;
  private String targetInstanceNo;
  private String batchNo;
  private Long relatedFileId;
  private String reason;
  private String operatorId;
  private String approvalId;
  private String strategy;
}
