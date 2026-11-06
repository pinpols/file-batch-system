package io.github.pinpols.batch.console.support.persistence;

/**
 * 把 Console 已解析的租户写入当前平台库事务的 {@code app.tenant_id}，供强制 RLS 的表按租户校验（ARCH-20261103-01）。
 *
 * <p>只在平台库事务内生效（{@code set_config(..., true)} 随事务结束复位）；无事务时不做任何事。平台库账号可绕过 RLS
 * 时写入不改变行为，改用普通运行时角色后才成为必要条件。
 */
@FunctionalInterface
public interface TenantDatabaseContext {

  /** 不绑定数据库租户上下文；用于不访问数据库的单元测试。 */
  TenantDatabaseContext NONE = tenantId -> {};

  void bind(String tenantId);
}
