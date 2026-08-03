package io.github.pinpols.batch.console.shared.security;

import java.util.Set;

/**
 * 控制台认证后的最小身份载荷。
 *
 * <p>仅保存用户名、租户和已认证权限集合，不包含 RBAC 查询或授权策略；认证和授权策略仍由 rbac context 持有。
 */
public record ConsolePrincipal(String username, String tenantId, Set<String> authorities) {}
