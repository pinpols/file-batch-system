package io.github.pinpols.batch.console.domain.rbac.param;

import lombok.Builder;

/** 绑定一次insertProvisioned操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record ConsoleUserAccountInsertProvisionedParam(
    String tenantId,
    String username,
    String displayName,
    String passwordHash,
    String authoritiesCsv) {

  @Override
  public String toString() {
    // 持久化输入可能包含凭据或业务载荷，诊断信息不展开字段值。
    return "ConsoleUserAccountInsertProvisionedParam[redacted]";
  }
}
