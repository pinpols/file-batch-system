package com.example.batch.console.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table(schema = "batch", name = "console_user_account")
public class ConsoleUserAccountEntity {

  @Id private Long id;

  @Column("tenant_id")
  private String tenantId;

  @Column("username")
  private String username;

  @Column("display_name")
  private String displayName;

  @Column("password_hash")
  private String passwordHash;

  @Column("authorities_csv")
  private String authoritiesCsv;

  @Column("enabled")
  private boolean enabled;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
  }

  public String getAuthoritiesCsv() {
    return authoritiesCsv;
  }

  public void setAuthoritiesCsv(String authoritiesCsv) {
    this.authoritiesCsv = authoritiesCsv;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }
}
