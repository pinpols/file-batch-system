package io.github.pinpols.batch.console.shared.query;

/** 需要读取当前调用方租户作用域的查询端口。 */
public interface TenantScopeResolver extends TenantIdResolver {

  String currentTenantScopeOrNull();
}
