package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.enums.CompensationCommandStatus;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.infrastructure.scheduler.StaleCompensationCommandReconciler;
import com.example.batch.orchestrator.infrastructure.tenant.ActiveTenantProvider;
import com.example.batch.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * IT:验证 stale 补偿命令扫描改为按租户路由后,两个不同租户下各自过期的 RUNNING 命令都能被正确标为 FAILED。
 *
 * <p>防污染:租户 ID 和 command_no 均含 nanoTime 后缀,afterEach 精确清理本次测试数据。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "batch.startup-self-check.enabled=false",
      "batch.compensation.stale-running-reconciler.enabled=true"
    })
class StaleCompensationReconcilerTenantRoutingIntegrationTest extends AbstractIntegrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private StaleCompensationCommandReconciler reconciler;
  @MockitoSpyBean private ActiveTenantProvider activeTenantProvider;

  private String tenantA;
  private String tenantB;
  private long suffix;

  @AfterEach
  void cleanup() {
    if (tenantA != null) {
      jdbcTemplate.update("DELETE FROM batch.compensation_command WHERE tenant_id = ?", tenantA);
      jdbcTemplate.update("DELETE FROM batch.tenant WHERE tenant_id = ?", tenantA);
    }
    if (tenantB != null) {
      jdbcTemplate.update("DELETE FROM batch.compensation_command WHERE tenant_id = ?", tenantB);
      jdbcTemplate.update("DELETE FROM batch.tenant WHERE tenant_id = ?", tenantB);
    }
  }

  @Test
  @DisplayName("两租户各一条过期 RUNNING 命令,tick 后两条均变 FAILED")
  void shouldMarkStaleCommandsFailedForBothTenants() {
    // arrange
    suffix = System.nanoTime();
    tenantA = "lt3a-" + suffix;
    tenantB = "lt3b-" + suffix;

    insertTenant(tenantA);
    insertTenant(tenantB);

    long idA = insertExpiredRunningCommand(tenantA, "cmd-a-" + suffix);
    long idB = insertExpiredRunningCommand(tenantB, "cmd-b-" + suffix);

    // 强制 ActiveTenantProvider 缓存失效,使本次 reconcile 看到新插入租户
    activeTenantProvider.invalidateCache();

    // 临时把 timeout 缩到极短,确保刚插入的 RUNNING 命令被判为 stale
    ReflectionTestUtils.setField(reconciler, "timeoutSeconds", 1L);
    ReflectionTestUtils.setField(reconciler, "batchSize", 100);

    // act
    reconciler.reconcile();

    // assert
    String statusA = queryStatus(tenantA, idA);
    String statusB = queryStatus(tenantB, idB);

    assertThat(statusA)
        .as("租户 %s 的命令 %d 应被标为 FAILED", tenantA, idA)
        .isEqualTo(CompensationCommandStatus.FAILED.code());
    assertThat(statusB)
        .as("租户 %s 的命令 %d 应被标为 FAILED", tenantB, idB)
        .isEqualTo(CompensationCommandStatus.FAILED.code());
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private void insertTenant(String tenantId) {
    jdbcTemplate.update(
        "INSERT INTO batch.tenant (tenant_id, tenant_name, status, created_by)"
            + " VALUES (?, ?, 'ACTIVE', 'it')",
        tenantId,
        "IT Tenant " + tenantId);
  }

  private long insertExpiredRunningCommand(String tenantId, String commandNo) {
    jdbcTemplate.update(
        """
        INSERT INTO batch.compensation_command
            (tenant_id, command_no, compensation_type, target_id,
             command_status, created_at)
        VALUES (?, ?, 'JOB', NULL,
                ?, NOW() - INTERVAL '2 hours')
        """,
        tenantId,
        commandNo,
        CompensationCommandStatus.RUNNING.code());
    return jdbcTemplate.queryForObject(
        "SELECT id FROM batch.compensation_command WHERE tenant_id = ? AND command_no = ?",
        Long.class,
        tenantId,
        commandNo);
  }

  private String queryStatus(String tenantId, long id) {
    return jdbcTemplate.queryForObject(
        "SELECT command_status FROM batch.compensation_command WHERE tenant_id = ? AND id = ?",
        String.class,
        tenantId,
        id);
  }
}
