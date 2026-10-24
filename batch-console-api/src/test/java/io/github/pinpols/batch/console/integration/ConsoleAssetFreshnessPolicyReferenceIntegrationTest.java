package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.param.AssetFreshnessPolicyUpsertParam;
import io.github.pinpols.batch.console.mapper.ConsoleAssetFreshnessPolicyMapper;
import io.github.pinpols.batch.console.service.ConsoleAssetFreshnessPolicyService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("资产新鲜度策略的 Job 引用与租户完整性")
class ConsoleAssetFreshnessPolicyReferenceIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private ConsoleAssetFreshnessPolicyMapper mapper;

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private ConsoleAssetFreshnessPolicyService service;

  @Test
  @DisplayName("策略不能引用同租户中不存在的 Job")
  void shouldRejectPolicyWithoutSameTenantJobDefinition() {
    final String selectCountSql = """
        select count(*) from batch.asset_freshness_policy where tenant_id = ? and asset_code = ?
    """;

    String tenantId = "freshness_" + UUID.randomUUID().toString().replace("-", "");
    String jobCode = "JOB_" + UUID.randomUUID().toString().replace("-", "");

    assertThatThrownBy(() -> service.upsert(policy(tenantId, jobCode)))
        .isInstanceOf(BizException.class)
        .satisfies(
            ex -> assertThat(((BizException) ex).getCode()).isEqualTo(ResultCode.INVALID_ARGUMENT));

    assertThatThrownBy(() -> mapper.upsert(policy(tenantId, jobCode)))
        .isInstanceOf(DataIntegrityViolationException.class);

    Long selectedAssetFreshnessPolicyCount =
        jdbc.queryForObject(selectCountSql, Long.class, tenantId, jobCode);
    assertThat(selectedAssetFreshnessPolicyCount).isZero();
  }

  @Test
  @DisplayName("相同作业编码属于另一租户时, 数据库拒绝写入策略")
  void shouldRejectPolicyWhenJobCodeExistsOnlyInAnotherTenant() {
    String tenantId = "freshness_" + UUID.randomUUID().toString().replace("-", "");
    String otherTenantId = "freshness_" + UUID.randomUUID().toString().replace("-", "");
    String jobCode = "JOB_" + UUID.randomUUID().toString().replace("-", "");
    insertJob(otherTenantId, jobCode, true);

    assertThatThrownBy(() -> mapper.upsert(policy(tenantId, jobCode)))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(policyCount(tenantId, jobCode)).isZero();
  }

  @Test
  @DisplayName("策略可在产物首次生成前配置, 删除所属作业时随配置一并删除")
  void shouldKeepPolicyIndependentFromAssetOutputAndCascadeWithJobDeletion() {
    final String selectCountSql = """
        select count(*) from batch.data_asset where tenant_id = ? and asset_code = ?
    """;
    final String deleteJobDefinitionSql = """
        delete from batch.job_definition where tenant_id = ? and job_code = ?
    """;

    String tenantId = "freshness_" + UUID.randomUUID().toString().replace("-", "");
    String jobCode = "JOB_" + UUID.randomUUID().toString().replace("-", "");
    insertJob(tenantId, jobCode, false);

    service.upsert(policy(tenantId, jobCode));
    assertThat(policyCount(tenantId, jobCode)).isEqualTo(1L);
    Long selectedDataAssetCount =
        jdbc.queryForObject(selectCountSql, Long.class, tenantId, jobCode);
    assertThat(selectedDataAssetCount).isZero();

    jdbc.update(deleteJobDefinitionSql, tenantId, jobCode);

    assertThat(policyCount(tenantId, jobCode)).isZero();
  }

  private void insertJob(String tenantId, String jobCode, boolean enabled) {
    final String insertJobDefinitionSql = """
        insert into batch.job_definition(tenant_id, job_code, job_name, job_type, schedule_type, timezone,\s\
        enabled)
        values (?, ?, 'Freshness fixture', 'GENERAL', 'MANUAL', 'UTC', ?)
        """;

    jdbc.update(insertJobDefinitionSql, tenantId, jobCode, enabled);
  }

  private long policyCount(String tenantId, String jobCode) {
    final String selectCountSql = """
        select count(*) from batch.asset_freshness_policy where tenant_id = ? and asset_code = ?
    """;

    return jdbc.queryForObject(selectCountSql, Long.class, tenantId, jobCode);
  }

  private static AssetFreshnessPolicyUpsertParam policy(String tenantId, String jobCode) {
    return AssetFreshnessPolicyUpsertParam.builder()
        .tenantId(tenantId)
        .assetCode(jobCode)
        .assetType("JOB")
        .expectedByLocalTime(LocalTime.NOON)
        .timezone("UTC")
        .staleAfterSeconds(60)
        .lookbackDays(1)
        .severity("WARN")
        .enabled(true)
        .build();
  }
}
