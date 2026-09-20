package io.github.pinpols.batch.console.domain.rbac.entity;

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

  /** 建议更新密码提示标志(V174);默认 false,出厂内置账号 / reset 路径置 true。 */
  private boolean mustChangePassword;
}
