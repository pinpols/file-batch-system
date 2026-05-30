package com.example.batch.console.domain.file.web.request;

import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ArchiveFileRequest {

  @ValidTenantId private String tenantId;
  @NotNull private Long fileId;

  @Size(max = 512, message = "reason too long (max 512)")
  private String reason;
}
