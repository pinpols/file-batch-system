package io.github.pinpols.batch.console.domain.rbac.application.contract.request;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Data
public class ResetPasswordRequest {

    @NotBlank
    @Size(min = 8, max = 256)
    private String newPassword;
}
