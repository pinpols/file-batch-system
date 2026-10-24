package io.github.pinpols.batch.console.domain.rbac.param;

import lombok.Builder;

/** 绑定一次insert操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record ConsoleOidcIdentityInsertParam(
    String tenantId,
    long accountId,
    String issuer,
    String subject,
    String linkedBy) {}
