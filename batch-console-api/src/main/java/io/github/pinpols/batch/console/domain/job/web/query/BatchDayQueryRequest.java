package io.github.pinpols.batch.console.domain.job.web.query;

import io.github.pinpols.batch.common.validation.ValidBizDate;
import io.github.pinpols.batch.common.validation.ValidTenantId;
import io.github.pinpols.batch.console.web.query.PageQueryRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = false)
@Data
public class BatchDayQueryRequest extends PageQueryRequest {

  @ValidTenantId private String tenantId;

  @NotBlank
  @Size(max = 128, message = "calendarCode too long (max 128)")
  private String calendarCode;

  @ValidBizDate private String from;

  @ValidBizDate private String to;
}
