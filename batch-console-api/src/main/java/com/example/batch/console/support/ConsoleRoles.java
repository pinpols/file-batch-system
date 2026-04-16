package com.example.batch.console.support;

import java.util.Set;

public final class ConsoleRoles {

  public static final String ADMIN = "ROLE_ADMIN";
  public static final String AUDITOR = "ROLE_AUDITOR";
  public static final String CONFIG_ADMIN = "ROLE_CONFIG_ADMIN";
  public static final String TENANT_USER = "ROLE_TENANT_USER";
  public static final String USER = "ROLE_USER";

  private static final Set<String> GLOBAL_ROLES = Set.of(ADMIN, AUDITOR, CONFIG_ADMIN);

  private ConsoleRoles() {}

  /** 判断给定权限集合是否包含全局（跨租户）角色。 */
  public static boolean hasGlobalRole(Set<String> authorities) {
    for (String authority : authorities) {
      if (GLOBAL_ROLES.contains(authority)) {
        return true;
      }
    }
    return false;
  }
}
