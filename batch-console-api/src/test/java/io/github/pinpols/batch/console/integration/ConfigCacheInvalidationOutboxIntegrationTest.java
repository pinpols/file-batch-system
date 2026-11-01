package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.application.config.ConfigCacheInvalidationIntent;
import io.github.pinpols.batch.console.application.config.ConfigCacheInvalidationRelay;
import io.github.pinpols.batch.console.mapper.ConfigCacheInvalidationOutboxMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("配置缓存失效意图的事务和领取栅栏")
class ConfigCacheInvalidationOutboxIntegrationTest extends AbstractIntegrationTest {
  @Autowired
  private ConfigCacheInvalidationRelay relay;

  @Autowired
  private ConfigCacheInvalidationOutboxMapper outbox;

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  @Qualifier("transactionManager")
  private PlatformTransactionManager manager;

  private String tenant;
  private String createdRole;
  private String createdPolicy;

  @BeforeEach
  void resetQueue() {
    relay.stop();
    tenant = "cache-" + UUID.randomUUID().toString().substring(0, 8);
  }

  @AfterEach
  void cleanupOwnedData() {
    String deleteQuotaSql = "delete from batch.tenant_quota_policy where tenant_id = ?";
    String deleteIntentSql =
        "delete from batch.config_cache_invalidation_outbox where tenant_id = ?";
    jdbc.update(deleteQuotaSql, tenant);
    jdbc.update(deleteIntentSql, tenant);
    if (createdPolicy != null) {
      String dropPolicySql =
          "drop policy %s on batch.config_cache_invalidation_outbox".formatted(createdPolicy);
      jdbc.execute(dropPolicySql);
      createdPolicy = null;
    }
    if (createdRole != null) {
      String revokeRoleSql = "drop owned by %s".formatted(createdRole);
      String dropRoleSql = "drop role %s".formatted(createdRole);
      jdbc.execute(revokeRoleSql);
      jdbc.execute(dropRoleSql);
      createdRole = null;
    }
  }

  @Test
  @DisplayName("配额写入与意图同事务提交，回滚不保留意图")
  void quotaChangesRecordOnlyCommittedIntents() {
    String insert = """
        insert into batch.tenant_quota_policy (tenant_id, policy_code, max_running_jobs_per_tenant)
        values (?, 'quota', 10)
        """;
    jdbc.update(insert, tenant);
    assertThat(count(tenant)).isEqualTo(1);
    String rollbackUpdateSql = """
        update batch.tenant_quota_policy set max_running_jobs_per_tenant = 20 where tenant_id = ?
        """;
    TransactionTemplate transaction = new TransactionTemplate(manager);
    transaction.executeWithoutResult(status -> {
      jdbc.update(rollbackUpdateSql, tenant);
      status.setRollbackOnly();
    });
    assertThat(count(tenant)).isEqualTo(1);
    String updateQuotaSql = """
        update batch.tenant_quota_policy set max_running_jobs_per_tenant = 30 where tenant_id = ?
        """;
    String deleteQuotaSql = "delete from batch.tenant_quota_policy where tenant_id = ?";
    jdbc.update(updateQuotaSql, tenant);
    jdbc.update(deleteQuotaSql, tenant);
    assertThat(count(tenant)).isEqualTo(3);
  }

  @Test
  @DisplayName("过期领取被重领后，旧令牌不能确认或释放")
  void expiredClaimCannotAcknowledgeNewClaim() {
    outbox.insert(tenant, "tenant-quota-policy", "enabled-first");
    String prioritizeSql = """
        update batch.config_cache_invalidation_outbox set next_attempt_at = '-infinity'
         where tenant_id = ?
        """;
    jdbc.update(prioritizeSql, tenant);
    String oldToken = UUID.randomUUID().toString();
    String newToken = UUID.randomUUID().toString();
    TransactionTemplate transaction = new TransactionTemplate(manager);
    ConfigCacheInvalidationIntent first = transaction.execute(status -> {
      ConfigCacheInvalidationIntent intent = outbox.lockNext();
      assertThat(intent).isNotNull();
      assertThat(intent.tenantId()).isEqualTo(tenant);
      assertThat(outbox.claim(intent.id(), "other-tenant", oldToken)).isZero();
      assertThat(outbox.claim(intent.id(), intent.tenantId(), oldToken)).isOne();
      return intent;
    });
    String expireClaimSql = """
        update batch.config_cache_invalidation_outbox
           set claim_until = clock_timestamp() - interval '1 second' where id = ?
        """;
    jdbc.update(expireClaimSql, first.id());
    ConfigCacheInvalidationIntent second = transaction.execute(status -> {
      ConfigCacheInvalidationIntent intent = outbox.lockNext();
      assertThat(intent.id()).isEqualTo(first.id());
      assertThat(outbox.claim(intent.id(), intent.tenantId(), newToken)).isOne();
      return intent;
    });
    assertThat(outbox.acknowledge(second.id(), second.tenantId(), oldToken)).isZero();
    assertThat(outbox.retry(second.id(), second.tenantId(), oldToken)).isZero();
    assertThat(outbox.acknowledge(second.id(), "other-tenant", newToken)).isZero();
    assertThat(outbox.retry(second.id(), "other-tenant", newToken)).isZero();
    assertThat(outbox.acknowledge(second.id(), second.tenantId(), newToken)).isOne();
  }

  @Test
  @DisplayName("普通无绕过权限角色不能读取其他租户的失效意图")
  void ordinaryRoleCannotReadPendingIntents() {
    outbox.insert(tenant, "tenant-quota-policy", "enabled-first");
    String role = "config_cache_test_" + UUID.randomUUID().toString().replace("-", "");
    String createRoleSql = "create role %s nologin nosuperuser nobypassrls".formatted(role);
    jdbc.execute(createRoleSql);
    createdRole = role;
    String grantSchemaSql = "grant usage on schema batch to %s".formatted(role);
    String grantSelectSql =
        "grant select on batch.config_cache_invalidation_outbox to %s".formatted(role);
    jdbc.execute(grantSchemaSql);
    jdbc.execute(grantSelectSql);
    TransactionTemplate transaction = new TransactionTemplate(manager);
    Integer visible = transaction.execute(status -> {
      String setRoleSql = "set local role %s".formatted(role);
      String countIntentsSql = "select count(*) from batch.config_cache_invalidation_outbox";
      jdbc.execute(setRoleSql);
      return jdbc.queryForObject(countIntentsSql, Integer.class);
    });
    assertThat(visible).isZero();
  }

  @Test
  @DisplayName("独立投递角色需同时获得表权限和专属 RLS 策略才能领取确认")
  void shouldRequireTableGrantsAndPolicy_whenDeliveryRoleClaims() {
    outbox.insert(tenant, "tenant-quota-policy", "enabled-first");
    String prioritizeSql = """
        update batch.config_cache_invalidation_outbox set next_attempt_at = '-infinity'
         where tenant_id = ?
        """;
    jdbc.update(prioritizeSql, tenant);

    String role = "config_cache_delivery_" + UUID.randomUUID().toString().replace("-", "");
    String createRoleSql = "create role %s nologin nosuperuser nobypassrls".formatted(role);
    jdbc.execute(createRoleSql);
    createdRole = role;
    String grantSchemaSql = "grant usage on schema batch to %s".formatted(role);
    String grantTableSql = """
        grant select, update, delete on batch.config_cache_invalidation_outbox to %s
        """.formatted(role);
    jdbc.execute(grantSchemaSql);
    jdbc.execute(grantTableSql);

    String setRoleSql = "set local role %s".formatted(role);
    TransactionTemplate transaction = new TransactionTemplate(manager);
    ConfigCacheInvalidationIntent invisible = transaction.execute(status -> {
      jdbc.execute(setRoleSql);
      return outbox.lockNext();
    });
    assertThat(invisible).isNull();

    String policy = "config_cache_delivery_" + UUID.randomUUID().toString().replace("-", "");
    String createPolicySql = """
        create policy %s on batch.config_cache_invalidation_outbox to %s
        using (true) with check (true)
        """.formatted(policy, role);
    jdbc.execute(createPolicySql);
    createdPolicy = policy;
    String token = UUID.randomUUID().toString();
    transaction.executeWithoutResult(status -> {
      jdbc.execute(setRoleSql);
      ConfigCacheInvalidationIntent intent = outbox.lockNext();
      assertThat(intent).isNotNull();
      assertThat(intent.tenantId()).isEqualTo(tenant);
      assertThat(outbox.claim(intent.id(), intent.tenantId(), token)).isOne();
      assertThat(outbox.acknowledge(intent.id(), intent.tenantId(), token)).isOne();
    });
    assertThat(count(tenant)).isZero();
  }

  private int count(String tenant) {
    String sql = """
        select count(*) from batch.config_cache_invalidation_outbox
         where tenant_id = ? and config_type = 'tenant-quota-policy' and config_code = 'enabled-first'
        """;
    return jdbc.queryForObject(sql, Integer.class, tenant);
  }
}
