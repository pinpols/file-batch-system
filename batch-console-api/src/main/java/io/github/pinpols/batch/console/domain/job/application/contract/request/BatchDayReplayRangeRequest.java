package io.github.pinpols.batch.console.domain.job.application.contract.request;

import io.github.pinpols.batch.common.validation.ValidBizDate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.Data;

/** 经租户守护的 ADR-049 日期区间重放请求。 */
@Data
public class BatchDayReplayRangeRequest {

  @Size(max = 64)
  private String tenantId;

  @NotBlank
  @Size(max = 128)
  private String calendarCode;

  @NotBlank
  @ValidBizDate
  private String fromDate;

  @NotBlank
  @ValidBizDate
  private String throughDate;

  @NotBlank
  @Size(max = 64)
  private String scope;

  private List<@Size(max = 128) String> jobCodes;
  private List<Long> versionIds;
  private String resultPolicy;
  private String configVersionPolicy;
  private Integer configVersion;

  @NotBlank
  @Size(max = 512)
  private String reason;

  @NotBlank
  @Size(max = 128)
  private String requestedBy;

  private Boolean autoApprove = Boolean.FALSE;
  private String traceId;
  private String executionMode = "REPLAY";
  private String candidateSource = "EXISTING_INSTANCES";
  private UUID operationKey;
  private String previewToken;
}
