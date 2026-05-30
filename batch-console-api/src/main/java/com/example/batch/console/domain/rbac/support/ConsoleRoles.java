package com.example.batch.console.domain.rbac.support;

import java.util.Set;

/**
 * 控制台 4 角色模型(2026-05 重设计):
 *
 * <table>
 *   <tr><th></th><th>写</th><th>只读</th></tr>
 *   <tr><th>平台级(跨租户)</th><td>{@link #ADMIN}</td><td>{@link #AUDITOR}</td></tr>
 *   <tr><th>租户级(本租户)</th><td>{@link #TENANT_ADMIN}</td><td>{@link #TENANT_USER}</td></tr>
 * </table>
 *
 * <p>历史 {@code ROLE_CONFIG_ADMIN} 已合并升级为 {@link #ADMIN}(V149 迁移); {@code ROLE_USER} 保留兼容旧 JWT,语义等同
 * {@link #TENANT_USER}。
 */
public final class ConsoleRoles {

  public static final String ADMIN = "ROLE_ADMIN";
  public static final String AUDITOR = "ROLE_AUDITOR";
  public static final String TENANT_ADMIN = "ROLE_TENANT_ADMIN";
  public static final String TENANT_USER = "ROLE_TENANT_USER";

  /** 兼容旧 JWT 的过渡常量,新代码不要用,语义等同 {@link #TENANT_USER}。 */
  public static final String USER = "ROLE_USER";

  private static final Set<String> GLOBAL_ROLES = Set.of(ADMIN, AUDITOR);

  private ConsoleRoles() {}

  /** 判断给定权限集合是否包含全局(跨租户)角色。 */
  public static boolean hasGlobalRole(Set<String> authorities) {
    for (String authority : authorities) {
      if (GLOBAL_ROLES.contains(authority)) {
        return true;
      }
    }
    return false;
  }
}
