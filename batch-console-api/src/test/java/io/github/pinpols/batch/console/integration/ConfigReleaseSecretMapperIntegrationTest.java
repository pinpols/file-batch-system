package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.entity.ConfigReleaseEntity;
import io.github.pinpols.batch.console.domain.param.ConfigReleaseInsertParam;
import io.github.pinpols.batch.console.domain.param.ConfigReleaseStatusUpdateParam;
import io.github.pinpols.batch.console.domain.param.SecretVersionInsertParam;
import io.github.pinpols.batch.console.domain.rbac.entity.SecretVersionEntity;
import io.github.pinpols.batch.console.domain.rbac.mapper.SecretVersionMapper;
import io.github.pinpols.batch.console.mapper.ConfigReleaseMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 真实 PostgreSQL 验证具名参数绑定、可空字段和租户/CAS；测试事务回滚，业务提交另由 HTTP 用例覆盖。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "batch.startup-self-check.enabled=false")
@Transactional
@DisplayName("配置发布与密钥 Mapper：固定入参的真实字段绑定与状态约束")
class ConfigReleaseSecretMapperIntegrationTest extends AbstractIntegrationTest {

  private static final Instant FROM = Instant.parse("2026-01-02T03:04:05Z");
  private static final Instant UNTIL = Instant.parse("2026-11-12T13:14:15Z");

  private final ConfigReleaseMapper releases;
  private final SecretVersionMapper secrets;
  private final SecretPayloadProtector protector;
  private final JdbcTemplate jdbc;

  @Autowired
  ConfigReleaseSecretMapperIntegrationTest(
      ConfigReleaseMapper releases,
      SecretVersionMapper secrets,
      SecretPayloadProtector protector,
      JdbcTemplate jdbc) {
    this.releases = releases;
    this.secrets = secrets;
    this.protector = protector;
    this.jdbc = jdbc;
  }

  @Test
  @DisplayName("发布写入保留全部业务字段、JSONB和可空灰度，具名查询遵守租户及配置键")
  void shouldBindReleaseFields_andPreserveQueryIdentity() {
    String tenant = tenant();
    assertThat(releases.acquireVersionLock(tenant, "JOB", "mapper-job")).isEqualTo(1);
    insertRelease(tenant, 1, "DRAFT");
    Long id = releaseId(tenant, 1);

    ConfigReleaseEntity row = releases.selectById(tenant, id);
    assertThat(row.getTenantId()).isEqualTo(tenant);
    assertThat(row.getConfigType()).isEqualTo("JOB");
    assertThat(row.getConfigKey()).isEqualTo("mapper-job");
    assertThat(row.getConfigName()).isEqualTo("配置映射夹具");
    assertThat(row.getConfigStatus()).isEqualTo("DRAFT");
    assertThat(row.getVersionNo()).isEqualTo(1);
    assertThat(row.getGrayScope()).isNull();
    assertThat(row.getConfigPayload()).contains("mapper-job");
    assertThat(row.getEffectiveFromAt()).isEqualTo(FROM);
    assertThat(row.getEffectiveToAt()).isEqualTo(UNTIL);
    assertThat(row.getCreatedBy()).isEqualTo("creator");
    assertThat(row.getUpdatedBy()).isEqualTo("editor");
    assertThat(releases.selectById(tenant + "-other", id)).isNull();
    assertThat(releases.selectLatestVersionNo(tenant, "JOB", "mapper-job")).isEqualTo(1);
    assertThat(releases.selectLatestVersionNo(tenant, "JOB", "other-job")).isNull();
    assertThat(releases.selectLatestVersionNo(tenant, "WORKFLOW", "mapper-job")).isNull();
    assertThat(releases.selectLatestVersionNo(tenant + "-other", "JOB", "mapper-job"))
        .isNull();
  }

  @Test
  @DisplayName("上一生效版本排除草稿与已回滚版本，状态CAS保留租户、版本、前态和已有发布时间")
  void shouldPreserveReleaseCas_andPreviousEffectiveVersion() {
    String tenant = tenant();
    insertRelease(tenant, 1, "PUBLISHED");
    insertRelease(tenant, 2, "GRAY");
    insertRelease(tenant, 3, "DRAFT");
    insertRelease(tenant, 4, "ROLLED_BACK");
    assertThat(releases.selectPreviousEffective(tenant, "JOB", "mapper-job", 5).getVersionNo())
        .isEqualTo(2);
    assertThat(releases.selectPreviousEffective(tenant + "-other", "JOB", "mapper-job", 5))
        .isNull();
    Long id = releaseId(tenant, 2);
    ConfigReleaseStatusUpdateParam published = ConfigReleaseStatusUpdateParam.builder()
        .tenantId(tenant)
        .releaseId(id)
        .nextStatus("PUBLISHED")
        .expectedStatus("GRAY")
        .expectedVersionNo(2)
        .publishedAt(FROM)
        .rolledBackAt(null)
        .updatedBy("publisher")
        .build();
    assertThat(releases.updateConfigReleaseStatus(published)).isEqualTo(1);
    assertThat(releases.updateConfigReleaseStatus(published)).isZero();
    ConfigReleaseStatusUpdateParam wrongTenant = ConfigReleaseStatusUpdateParam.builder()
        .tenantId(tenant + "-other")
        .releaseId(id)
        .nextStatus("ROLLED_BACK")
        .expectedStatus("PUBLISHED")
        .expectedVersionNo(2)
        .updatedBy("other")
        .build();
    assertThat(releases.updateConfigReleaseStatus(wrongTenant)).isZero();
    ConfigReleaseStatusUpdateParam wrongVersion = ConfigReleaseStatusUpdateParam.builder()
        .tenantId(tenant)
        .releaseId(id)
        .nextStatus("ROLLED_BACK")
        .expectedStatus("PUBLISHED")
        .expectedVersionNo(1)
        .updatedBy("other")
        .build();
    assertThat(releases.updateConfigReleaseStatus(wrongVersion)).isZero();
    ConfigReleaseStatusUpdateParam rollback = ConfigReleaseStatusUpdateParam.builder()
        .tenantId(tenant)
        .releaseId(id)
        .nextStatus("ROLLED_BACK")
        .expectedStatus("PUBLISHED")
        .expectedVersionNo(2)
        .publishedAt(null)
        .rolledBackAt(UNTIL)
        .updatedBy("rollbacker")
        .build();
    assertThat(releases.updateConfigReleaseStatus(rollback)).isEqualTo(1);
    ConfigReleaseEntity row = releases.selectById(tenant, id);
    assertThat(row.getConfigStatus()).isEqualTo("ROLLED_BACK");
    assertThat(row.getPublishedAt()).isEqualTo(FROM);
    assertThat(row.getRolledBackAt()).isEqualTo(UNTIL);
    assertThat(row.getUpdatedBy()).isEqualTo("rollbacker");
    assertThat(releases.selectPreviousEffective(tenant, "JOB", "mapper-job", 5).getVersionNo())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("密钥写入保留受保护载荷、窗口与操作者，查询不回传载荷且降级限定同租户引用")
  void shouldBindSecretFields_andScopeCurrentVersion() {
    String tenant = tenant();
    String payload = protector.protect("{}");
    assertThat(secrets.acquireVersionLock(tenant, "mapper-ref")).isEqualTo(1);
    SecretVersionInsertParam input = SecretVersionInsertParam.builder()
        .tenantId(tenant)
        .secretRef("mapper-ref")
        .secretName("密钥映射夹具")
        .versionNo(1)
        .secretStatus("PUBLISHED")
        .currentVersion(true)
        .rotationWindowStartAt(FROM)
        .rotationWindowEndAt(UNTIL)
        .effectiveFromAt(FROM)
        .effectiveToAt(UNTIL)
        .secretPayloadJson(payload)
        .rotationReason("fixture rotation")
        .createdBy("creator")
        .updatedBy("editor")
        .build();
    assertThat(secrets.insertSecretVersion(input)).isEqualTo(1);
    Long id = secretId(tenant, 1);
    SecretVersionEntity row = secrets.selectById(tenant, id);
    assertThat(row.getTenantId()).isEqualTo(tenant);
    assertThat(row.getSecretRef()).isEqualTo("mapper-ref");
    assertThat(row.getSecretName()).isEqualTo("密钥映射夹具");
    assertThat(row.getVersionNo()).isEqualTo(1);
    assertThat(row.getSecretStatus()).isEqualTo("PUBLISHED");
    assertThat(row.getCurrentVersion()).isTrue();
    assertThat(row.getRotationWindowStartAt()).isEqualTo(FROM);
    assertThat(row.getRotationWindowEndAt()).isEqualTo(UNTIL);
    assertThat(row.getEffectiveFromAt()).isEqualTo(FROM);
    assertThat(row.getEffectiveToAt()).isEqualTo(UNTIL);
    assertThat(row.getRotationReason()).isEqualTo("fixture rotation");
    assertThat(row.getCreatedBy()).isEqualTo("creator");
    assertThat(row.getUpdatedBy()).isEqualTo("editor");
    assertThat(row.getSecretPayload()).isNull();
    String payloadEqualsSql = """
        select secret_payload = ?::jsonb from batch.secret_version where tenant_id = ? and id = ?
        """;
    assertThat(jdbc.queryForObject(payloadEqualsSql, Boolean.class, payload, tenant, id))
        .isTrue();
    assertThat(secrets.selectById(tenant + "-other", id)).isNull();
    assertThat(secrets.selectLatestVersionNo(tenant, "mapper-ref")).isEqualTo(1);
    assertThat(secrets.selectLatestVersionNo(tenant, "other-ref")).isNull();
    assertThat(secrets.selectLatestVersionNo(tenant + "-other", "mapper-ref")).isNull();
    assertThat(secrets.deactivateCurrentVersion(tenant + "-other", "mapper-ref"))
        .isZero();
    assertThat(secrets.deactivateCurrentVersion(tenant, "other-ref")).isZero();
    assertThat(secrets.deactivateCurrentVersion(tenant, "mapper-ref")).isEqualTo(1);
    assertThat(secrets.selectById(tenant, id).getCurrentVersion()).isFalse();
  }

  @Test
  @DisplayName("密钥可空字段保持空值，状态更新与撤销具名绑定保留租户和草稿/重复撤销条件")
  void shouldPreserveSecretStatusAndRevocationPredicates() {
    String tenant = tenant();
    SecretVersionInsertParam draft = SecretVersionInsertParam.builder()
        .tenantId(tenant)
        .secretRef("mapper-ref")
        .secretName("草稿夹具")
        .versionNo(1)
        .secretStatus("DRAFT")
        .currentVersion(false)
        .secretPayloadJson(protector.protect("{}"))
        .createdBy("creator")
        .updatedBy("creator")
        .build();
    assertThat(secrets.insertSecretVersion(draft)).isEqualTo(1);
    Long id = secretId(tenant, 1);
    SecretVersionEntity original = secrets.selectById(tenant, id);
    assertThat(original.getRotationWindowStartAt()).isNull();
    assertThat(original.getRotationWindowEndAt()).isNull();
    assertThat(original.getEffectiveFromAt()).isNull();
    assertThat(original.getEffectiveToAt()).isNull();
    assertThat(original.getRotationReason()).isNull();
    assertThat(secrets.revokeSecretVersion(tenant, id, "revoker", "fixture")).isZero();
    assertThat(secrets.updateSecretVersionStatus(tenant + "-other", id, "GRAY", "other"))
        .isZero();
    assertThat(secrets.updateSecretVersionStatus(tenant, id, "GRAY", "promoter"))
        .isEqualTo(1);
    SecretVersionEntity promoted = secrets.selectById(tenant, id);
    assertThat(promoted.getSecretStatus()).isEqualTo("GRAY");
    assertThat(promoted.getUpdatedBy()).isEqualTo("promoter");
    assertThat(promoted.getCurrentVersion()).isFalse();
    assertThat(secrets.revokeSecretVersion(tenant + "-other", id, "other", "fixture"))
        .isZero();
    assertThat(secrets.revokeSecretVersion(tenant, id, "revoker", "fixture")).isEqualTo(1);
    SecretVersionEntity revoked = secrets.selectById(tenant, id);
    assertThat(revoked.getRevokedAt()).isNotNull();
    assertThat(revoked.getRevokedBy()).isEqualTo("revoker");
    assertThat(revoked.getRevokeReason()).isEqualTo("fixture");
    assertThat(revoked.getUpdatedBy()).isEqualTo("revoker");
    assertThat(secrets.revokeSecretVersion(tenant, id, "second", "second")).isZero();
  }

  private void insertRelease(String tenant, int version, String status) {
    ConfigReleaseInsertParam input = ConfigReleaseInsertParam.builder()
        .tenantId(tenant)
        .configType("JOB")
        .configKey("mapper-job")
        .configName("配置映射夹具")
        .configStatus(status)
        .versionNo(version)
        .grayScopeJson(null)
        .configPayloadJson("{\"jobCode\":\"mapper-job\"}")
        .effectiveFromAt(FROM)
        .effectiveToAt(UNTIL)
        .createdBy("creator")
        .updatedBy("editor")
        .build();
    assertThat(releases.insertConfigRelease(input)).isEqualTo(1);
  }

  private Long releaseId(String tenant, int version) {
    String sql = """
        select id from batch.config_release where tenant_id = ? and config_key = 'mapper-job' and version_no = ?
        """;
    return jdbc.queryForObject(sql, Long.class, tenant, version);
  }

  private Long secretId(String tenant, int version) {
    String sql = """
        select id from batch.secret_version where tenant_id = ? and secret_ref = 'mapper-ref' and version_no = ?
        """;
    return jdbc.queryForObject(sql, Long.class, tenant, version);
  }

  private String tenant() {
    return "mapper-contract-" + Long.toUnsignedString(System.nanoTime());
  }
}
