package com.example.batch.console.domain.rbac.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ResetPasswordRequest {

  @NotBlank
  @Size(min = 8, max = 256)
  private String newPassword;
}
