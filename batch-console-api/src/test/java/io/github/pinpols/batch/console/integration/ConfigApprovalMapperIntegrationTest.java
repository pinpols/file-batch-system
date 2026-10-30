package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.ops.mapper.ConfigApprovalMapper;
import io.github.pinpols.batch.console.domain.ops.param.ConfigApprovalTransitionParam;
import io.github.pinpols.batch.console.domain.ops.view.ConfigApprovalRow;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("配置审批查询映射: 结果键保持驼峰形态,审批通过只影响一行")
class ConfigApprovalMapperIntegrationTest extends AbstractIntegrationTest {

  private final ConfigApprovalMapper configApprovalMapper;
  private final JdbcTemplate jdbcTemplate;

  @Autowired
  ConfigApprovalMapperIntegrationTest(
      ConfigApprovalMapper configApprovalMapper, JdbcTemplate jdbcTemplate) {
    this.configApprovalMapper = configApprovalMapper;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Test
  @DisplayName("查询与审批: 最新记录与按主键查询都保留驼峰键,审批写入返回一行")
  void shouldPreserveCamelCaseKeysForPostgresMapResults() {
    long suffix = BatchDateTimeSupport.utcEpochMillis();
    String tenantId = "t-config-approval-" + suffix;
    long releaseId = suffix;
    String insertConfigApprovalSql = """
        INSERT INTO batch.config_approval
          (tenant_id, release_id, approval_status, requested_by, review_comment,
           created_at, updated_at)
        VALUES (?, ?, 'PENDING', 'requester', 'integration test', now(), now())
        """;
    jdbcTemplate.update(insertConfigApprovalSql, tenantId, releaseId);

    ConfigApprovalRow latest = configApprovalMapper.selectLatestByRelease(tenantId, releaseId);
    assertThat(latest).isNotNull();
    assertThat(latest.tenantId()).isEqualTo(tenantId);
    assertThat(latest.releaseId()).isEqualTo(releaseId);
    assertThat(latest.approvalStatus()).isEqualTo("PENDING");
    assertThat(latest.requestedBy()).isEqualTo("requester");
    assertThat(latest.requestedAt()).isNotNull();
    assertThat(latest.createdAt()).isNotNull();
    assertThat(latest.updatedAt()).isNotNull();

    long approvalId = latest.id();
    ConfigApprovalRow byId = configApprovalMapper.selectById(tenantId, approvalId);
    assertThat(byId).isNotNull();
    assertThat(byId.releaseId()).isEqualTo(releaseId);
    assertThat(byId.approvalStatus()).isEqualTo("PENDING");

    int updated = configApprovalMapper.transition(
        ConfigApprovalTransitionParam.approved(tenantId, approvalId, "reviewer", "approved"));
    assertThat(updated).isEqualTo(1);
  }
}
