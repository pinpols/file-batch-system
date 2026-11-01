package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.rbac.entity.ConsoleUserAccountEntity;
import io.github.pinpols.batch.console.domain.rbac.mapper.ConsoleOidcIdentityMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    properties = {"batch.console.sso.oidc.enabled=false", "batch.startup-self-check.enabled=false"})
@DisplayName("OIDC 外部身份映射: PostgreSQL 唯一性与租户一致性约束")
class ConsoleOidcIdentityConstraintsIntegrationTest extends AbstractIntegrationTest {

  private final JdbcTemplate jdbcTemplate;
  private final ConsoleOidcIdentityMapper identityMapper;
  private String tenantId;
  private String otherTenantId;
  private String username;
  private long accountId;

  @Autowired
  ConsoleOidcIdentityConstraintsIntegrationTest(
      JdbcTemplate jdbcTemplate, ConsoleOidcIdentityMapper identityMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.identityMapper = identityMapper;
  }

  @BeforeEach
  void setUp() {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    tenantId = "oidc-it-" + suffix.substring(0, 20);
    otherTenantId = "oidc-other-" + suffix.substring(0, 20);
    username = "oidc-user-" + suffix;
    insertTenant(tenantId);
    insertTenant(otherTenantId);
    accountId = insertAccount(tenantId, username);
  }

  @AfterEach
  void cleanUp() {
    String deleteConsoleUserAccountSql = """
        delete from batch.console_user_account where username = ?
    """;
    jdbcTemplate.update(deleteConsoleUserAccountSql, username);
    String deleteTenantsSql = """
        delete from batch.tenant where tenant_id in (?, ?)
    """;
    jdbcTemplate.update(deleteTenantsSql, tenantId, otherTenantId);
  }

  @Test
  @DisplayName("复合外键拒绝把一个租户的本地账号绑定到另一个租户")
  void shouldRejectCrossTenantBinding_whenIdentityTenantDiffers() {
    assertThatThrownBy(() -> insertIdentity(otherTenantId, accountId, "subject-cross-tenant"))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(identityCount()).isZero();
  }

  @Test
  @DisplayName("唯一约束拒绝同租户重复 subject，也拒绝一个账号绑定多个 subject")
  void shouldRejectDuplicateBindings_whenSubjectOrAccountAlreadyBound() {
    insertIdentity(tenantId, accountId, "subject-one");

    assertThatThrownBy(() -> insertIdentity(tenantId, accountId, "subject-one"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertIdentity(tenantId, accountId, "subject-two"))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(identityCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("认证查询只返回已启用的精确租户主体映射")
  void shouldFindEnabledMappedAccount_whenTenantIssuerAndSubjectMatch() {
    insertIdentity(tenantId, accountId, "subject-exact");

    ConsoleUserAccountEntity mapped = identityMapper
        .findEnabledAccount(tenantId, "https://idp.example.test", "subject-exact")
        .orElseThrow();
    assertThat(mapped.getUsername()).isEqualTo(username);
    assertThat(mapped.getTenantId()).isEqualTo(tenantId);
    assertThat(mapped.getAuthoritiesCsv()).isEqualTo("ROLE_TENANT_USER");

    String disableConsoleUserAccountSql = """
        update batch.console_user_account set enabled = false where id = ?
    """;
    jdbcTemplate.update(disableConsoleUserAccountSql, accountId);
    assertThat(identityMapper.findEnabledAccount(
            tenantId, "https://idp.example.test", "subject-exact"))
        .isEmpty();
  }

  @Test
  @DisplayName("身份查询联表返回账号名称且不跨租户")
  void shouldMapIdentityViewWithAccountNameWithinTenant() {
    insertIdentity(tenantId, accountId, "subject-view");

    var identities = identityMapper.findByTenant(tenantId);
    assertThat(identities).hasSize(1);
    assertThat(identities.getFirst().getUsername()).isEqualTo(username);
    assertThat(identities.getFirst().getSubject()).isEqualTo("subject-view");
    assertThat(identityMapper.findByAccountAndTenant(accountId, tenantId))
        .get()
        .satisfies(identity -> assertThat(identity.getUsername()).isEqualTo(username));
    assertThat(identityMapper.findByAccountAndTenant(accountId, otherTenantId)).isEmpty();
    assertThat(identityMapper.findByTenant(otherTenantId)).isEmpty();
  }

  private void insertTenant(String id) {
    String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status, created_by)
        values (?, ?, 'ACTIVE', 'oidc-it')
        """;
    jdbcTemplate.update(insertTenantSql, id, id);
  }

  private long insertAccount(String tenant, String accountUsername) {
    String insertConsoleUserAccountSql = """
        insert into batch.console_user_account
            (tenant_id, username, display_name, password_hash, authorities_csv, enabled)
        values (?, ?, 'OIDC IT', 'test-only-not-a-password', 'ROLE_TENANT_USER', true)
        returning id
        """;
    return jdbcTemplate.queryForObject(
        insertConsoleUserAccountSql, Long.class, tenant, accountUsername);
  }

  private void insertIdentity(String tenant, long account, String subject) {
    String insertExternalIdentitySql = """
        insert into batch.console_external_identity
            (tenant_id, account_id, issuer, subject, linked_by)
        values (?, ?, 'https://idp.example.test', ?, 'oidc-it-admin')
        """;
    jdbcTemplate.update(insertExternalIdentitySql, tenant, account, subject);
  }

  private int identityCount() {
    String identityCountSql = """
        select count(*) from batch.console_external_identity where tenant_id in (?, ?)
        """;
    return jdbcTemplate.queryForObject(identityCountSql, Integer.class, tenantId, otherTenantId);
  }
}
