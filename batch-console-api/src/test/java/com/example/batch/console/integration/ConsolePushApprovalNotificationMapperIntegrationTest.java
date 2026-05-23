package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.BatchConsoleApiApplication;
import com.example.batch.console.domain.entity.ConsolePushApprovalNotificationEntity;
import com.example.batch.console.mapper.ConsolePushApprovalNotificationMapper;
import com.example.batch.console.support.push.PendingApprovalNotification;
import com.example.batch.testing.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** ConsolePushApprovalNotificationMapper IT:验证 SQL 过滤 + ON CONFLICT 幂等。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ConsolePushApprovalNotificationMapperIntegrationTest extends AbstractIntegrationTest {

  @Autowired private ConsolePushApprovalNotificationMapper mapper;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void findPendingShouldReturnTerminalApprovalsWithRequester() {
    String tenant = "t-papp-" + BatchDateTimeSupport.utcEpochMillis();
    String no = insertApproval(tenant, "CATCH_UP", "APPROVED", "alice", "bob", "ok", "0 minute");

    List<PendingApprovalNotification> pending = mapper.findPending(10, 50);

    PendingApprovalNotification mine =
        pending.stream().filter(p -> p.getApprovalNo().equals(no)).findFirst().get();
    assertThat(mine.getRequesterId()).isEqualTo("alice");
    assertThat(mine.getApproverId()).isEqualTo("bob");
    assertThat(mine.getApprovalStatus()).isEqualTo("APPROVED");
    assertThat(mine.getApprovalType()).isEqualTo("CATCH_UP");
  }

  @Test
  void findPendingShouldExcludePending() {
    String tenant = "t-papp-" + BatchDateTimeSupport.utcEpochMillis();
    String no = insertApproval(tenant, "CATCH_UP", "PENDING", "alice", null, null, "0 minute");

    List<PendingApprovalNotification> pending = mapper.findPending(10, 50);

    assertThat(pending).extracting(PendingApprovalNotification::getApprovalNo).doesNotContain(no);
  }

  @Test
  void findPendingShouldExcludeNullRequester() {
    String tenant = "t-papp-" + BatchDateTimeSupport.utcEpochMillis();
    String no = insertApproval(tenant, "COMPENSATION", "APPROVED", null, "bob", "ok", "0 minute");

    List<PendingApprovalNotification> pending = mapper.findPending(10, 50);

    assertThat(pending).extracting(PendingApprovalNotification::getApprovalNo).doesNotContain(no);
  }

  @Test
  void findPendingShouldExcludeOutsideLookbackWindow() {
    String tenant = "t-papp-" + BatchDateTimeSupport.utcEpochMillis();
    String no =
        insertApproval(tenant, "DOWNLOAD", "REJECTED", "alice", "bob", "no", "30 minute");

    List<PendingApprovalNotification> pending = mapper.findPending(10, 50);

    assertThat(pending).extracting(PendingApprovalNotification::getApprovalNo).doesNotContain(no);
  }

  @Test
  void findPendingShouldExcludeAlreadyNotified() {
    String tenant = "t-papp-" + BatchDateTimeSupport.utcEpochMillis();
    String no = insertApproval(tenant, "CATCH_UP", "EXECUTED", "alice", "bob", "done", "0 minute");
    ConsolePushApprovalNotificationEntity n = new ConsolePushApprovalNotificationEntity();
    n.setTenantId(tenant);
    n.setApprovalNo(no);
    mapper.insertIgnore(n);

    List<PendingApprovalNotification> pending = mapper.findPending(10, 50);

    assertThat(pending).extracting(PendingApprovalNotification::getApprovalNo).doesNotContain(no);
  }

  @Test
  void insertIgnoreShouldReturnOneFirstThenZeroOnConflict() {
    String tenant = "t-papp-" + BatchDateTimeSupport.utcEpochMillis();
    ConsolePushApprovalNotificationEntity n = new ConsolePushApprovalNotificationEntity();
    n.setTenantId(tenant);
    n.setApprovalNo("dup-no-1");

    int first = mapper.insertIgnore(n);
    int second = mapper.insertIgnore(n);

    assertThat(first).isEqualTo(1);
    assertThat(second).isZero();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private String insertApproval(
      String tenantId,
      String approvalType,
      String status,
      String requester,
      String approver,
      String reason,
      String approvedAgo) {
    String no = approvalType + "-" + System.nanoTime();
    boolean terminal =
        "APPROVED".equals(status) || "REJECTED".equals(status) || "EXECUTED".equals(status);
    String approvalReason = "APPROVED".equals(status) || "EXECUTED".equals(status) ? reason : null;
    String rejectionReason = "REJECTED".equals(status) ? reason : null;
    jdbc.update(
        """
        INSERT INTO batch.approval_command
          (tenant_id, approval_no, approval_type, action_type, target_type, target_id,
           payload_json, approval_status, requester_id, approver_id,
           approval_reason, rejection_reason, approved_at,
           created_at, updated_at)
        VALUES (?, ?, ?, ?, 'JOB_INSTANCE', '1',
                cast('{}' as jsonb), ?, ?, ?,
                ?, ?,
                CASE WHEN ? THEN now() - (?::interval) ELSE NULL END,
                now(), now())
        """,
        tenantId,
        no,
        approvalType,
        approvalType,
        status,
        requester,
        approver,
        approvalReason,
        rejectionReason,
        terminal,
        approvedAgo);
    return no;
  }
}
