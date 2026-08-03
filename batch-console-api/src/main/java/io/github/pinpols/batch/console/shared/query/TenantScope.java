package io.github.pinpols.batch.console.shared.query;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;

/**
 * 租户作用域的通用非空断言。
 *
 * <p>仅用于已经由租户解析 Port 收敛过的租户查询,防止可空租户参数让查询退化为全租户扫描。
 * 全局管理员跨租查询和按父 ID 反查的合法 null 作用域不得调用本方法。
 */
public final class TenantScope {

  private TenantScope() {}

  /**
   * 断言租户作用域 tenantId 非空,返回原值以便链式使用。
   *
   * @param tenantId 已经过租户解析 Port 解析的租户 ID
   * @return 非空的 tenantId
   * @throws BizException null / 空 / 全空白 → {@code FORBIDDEN error.tenant.context_missing}
   */
  public static String requireTenant(String tenantId) {
    if (tenantId == null || tenantId.isBlank()) {
      throw BizException.of(ResultCode.FORBIDDEN, "error.tenant.context_missing");
    }
    return tenantId;
  }
}
