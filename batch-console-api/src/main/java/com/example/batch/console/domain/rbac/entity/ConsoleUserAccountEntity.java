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
}
