package io.github.pinpols.batch.console.domain.rbac.param;

import lombok.Builder;

import java.time.Instant;
/** 绑定一次insert操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record ConsoleApiKeyInsertParam(
    String tenantId,
    String keyName,
    String keyPrefix,
    String keyHash,
    String salt,
    String keyHashAlgo,
    String scopes,
    Instant expiresAt,
    String operator) {

  @Override
  public String toString() {
    // 持久化输入可能包含凭据或业务载荷，诊断信息不展开字段值。
    return "ConsoleApiKeyInsertParam[redacted]";
  }
}
