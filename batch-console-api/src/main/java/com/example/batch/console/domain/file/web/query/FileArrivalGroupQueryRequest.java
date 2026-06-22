package com.example.batch.console.domain.file.web.query;

import com.example.batch.common.validation.ValidTenantId;
import com.example.batch.console.web.query.PageQueryRequest;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = false)
@Data
public class FileArrivalGroupQueryRequest extends PageQueryRequest {

  @ValidTenantId private String tenantId;

  @Size(max = 128, message = "fileGroupCode too long (max 128)")
  private String fileGroupCode;

  @Size(max = 32, message = "arrivalState too long (max 32)")
  private String arrivalState;
}
