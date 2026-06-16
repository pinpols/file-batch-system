package com.example.batch.console.domain.rbac.entity;

import lombok.Data;

@Data
public class ConsoleUserAccountEntity {

  private Long id;

  private String tenantId;

  private String username;

  private String displayName;

  private String passwordHash;

  private String authoritiesCsv;

  private boolean enabled;

  /** 首次登录强制改密标志(V174);默认 false 保持现行为,出厂内置账号 / reset 路径置 true。 */
  private boolean mustChangePassword;
}
