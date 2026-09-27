package io.github.pinpols.batch.console.domain.rbac.support;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.Collection;
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
 * <p>历史角色已由数据库迁移收敛到这四类正式角色，运行时不保留旧角色兼容分支。
 */
public final class ConsoleRoles {

  public static final String ADMIN = "ROLE_ADMIN";
  public static final String AUDITOR = "ROLE_AUDITOR";
  public static final String TENANT_ADMIN = "ROLE_TENANT_ADMIN";
  public static final String TENANT_USER = "ROLE_TENANT_USER";

  public static final Set<String> ALL = Set.of(ADMIN, AUDITOR, TENANT_ADMIN, TENANT_USER);
  private static final Set<String> GLOBAL_ROLES = Set.of(ADMIN, AUDITOR);

  private ConsoleRoles() {}

  /** 角色集合非空且全部属于四类正式角色。 */
  public static boolean isFormalRoleSet(Collection<String> authorities) {
    return EmptyChecks.isNotEmpty(authorities) && ALL.containsAll(authorities);
  }

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
