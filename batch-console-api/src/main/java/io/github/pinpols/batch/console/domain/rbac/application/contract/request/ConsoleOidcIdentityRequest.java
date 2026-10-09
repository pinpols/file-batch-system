package io.github.pinpols.batch.console.domain.rbac.application.contract.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 由全局管理员将 IdP 的稳定 subject 绑定到既有本地账号。 */
public record ConsoleOidcIdentityRequest(
    @NotBlank @Size(max = 128) String username,
    @NotBlank @Size(max = 255) String subject) {}
