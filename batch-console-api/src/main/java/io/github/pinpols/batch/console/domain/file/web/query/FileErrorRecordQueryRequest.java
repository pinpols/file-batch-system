package io.github.pinpols.batch.console.domain.file.web.query;

import io.github.pinpols.batch.common.validation.ValidTenantId;
import io.github.pinpols.batch.console.web.query.PageQueryRequest;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = false)
@Data
public class FileErrorRecordQueryRequest extends PageQueryRequest {

  @ValidTenantId private String tenantId;
  private Long fileId;

  @Size(max = 64, message = "errorStage too long (max 64)")
  private String errorStage;

  @Size(max = 128, message = "errorCode too long (max 128)")
  private String errorCode;

  private Boolean skipped;
}
