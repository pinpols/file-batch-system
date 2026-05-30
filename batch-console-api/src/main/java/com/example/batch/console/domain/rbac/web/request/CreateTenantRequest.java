package com.example.batch.console.domain.rbac.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateTenantRequest {

  @NotBlank
  @Size(min = 2, max = 64)
  @Pattern(
      regexp = "^[a-z0-9][a-z0-9\\-]*[a-z0-9]$",
      message = "tenant_id must be lowercase alphanumeric with hyphens, e.g. my-tenant")
  private String tenantId;

  @NotBlank
  @Size(max = 256)
  private String tenantName;

  @Size(max = 512)
  private String description;

  @NotBlank
  @Size(min = 2, max = 128)
  @Pattern(
      regexp = "^[a-zA-Z0-9][a-zA-Z0-9._\\-]*$",
      message =
          "username must start with alphanumeric and contain only letters, digits,"
              + " '.', '_', '-'")
  private String username;

  @NotBlank
  @Size(min = 8, max = 256)
  private String password;
}
