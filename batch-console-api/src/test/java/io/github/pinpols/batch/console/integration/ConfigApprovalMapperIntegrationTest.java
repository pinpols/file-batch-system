package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.ops.mapper.ConfigApprovalMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
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
  void shouldPreserveCamelCaseKeysForPostgresMapResults() {
    long suffix = BatchDateTimeSupport.utcEpochMillis();
    String tenantId = "t-config-approval-" + suffix;
    long releaseId = suffix;
    jdbcTemplate.update("""
        INSERT INTO batch.config_approval
          (tenant_id, release_id, approval_status, requested_by, review_comment,
           created_at, updated_at)
        VALUES (?, ?, 'PENDING', 'requester', 'integration test', now(), now())
        """, tenantId, releaseId);

    Map<String, Object> latest = configApprovalMapper.selectLatestByRelease(tenantId, releaseId);
    assertThat(latest)
        .containsEntry("tenantId", tenantId)
        .containsEntry("releaseId", releaseId)
        .containsEntry("approvalStatus", "PENDING")
        .containsEntry("requestedBy", "requester")
        .containsKeys("requestedAt", "createdAt", "updatedAt");

    long approvalId = ((Number) latest.get("id")).longValue();
    Map<String, Object> byId = configApprovalMapper.selectById(tenantId, approvalId);
    assertThat(byId)
        .containsEntry("releaseId", releaseId)
        .containsEntry("approvalStatus", "PENDING");

    int updated = configApprovalMapper.approve(Map.of(
        "tenantId",
        tenantId,
        "id",
        approvalId,
        "reviewedBy",
        "reviewer",
        "reviewComment",
        "approved"));
    assertThat(updated).isEqualTo(1);
  }
}
