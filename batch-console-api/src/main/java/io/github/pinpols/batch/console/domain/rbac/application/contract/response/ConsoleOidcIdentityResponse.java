package io.github.pinpols.batch.console.domain.rbac.application.contract.response;

import java.time.Instant;

/** 管理员维护的 OIDC 主体绑定；subject 仅限全局管理员 API 返回。 */
public record ConsoleOidcIdentityResponse(
    long id,
    String tenantId,
    long accountId,
    String username,
    String issuer,
    String subject,
    String linkedBy,
    Instant linkedAt) {}
