package io.github.pinpols.batch.console.shared.query;

/** 只暴露租户 ID 解析能力，避免通用查询工具依赖具体认证守卫。 */
@FunctionalInterface
public interface TenantIdResolver {

  String resolveTenant(String requestTenantId);
}
