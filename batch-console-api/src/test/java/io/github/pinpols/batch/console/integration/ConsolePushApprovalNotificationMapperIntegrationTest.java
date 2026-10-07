package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.notification.entity.ConsolePushApprovalNotificationEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsolePushApprovalNotificationMapper;
import io.github.pinpols.batch.console.domain.notification.support.PendingApprovalNotification;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** ConsolePushApprovalNotificationMapper IT:验证 SQL 过滤 + ON CONFLICT 幂等。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("审批通知待发查询映射: 终态筛选,申请人与时窗过滤及幂等写入")
class ConsolePushApprovalNotificationMapperIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private ConsolePushApprovalNotificationMapper mapper;

  @Autowired
  private JdbcTemplate jdbc;

  @Test
  @DisplayName("筛选待通知审批单: 已批准的终态单被选中,申请人,审批人与类型字段带出")
  void shouldReturnTerminalApprovals_whenApprovalEligible() {
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
  @DisplayName("仍待审批的单: 不出现在待通知列表中")
  void shouldExcludeApprovals_whenStatusNotTerminal() {
    String tenant = "t-papp-" + BatchDateTimeSupport.utcEpochMillis();
    String no = insertApproval(tenant, "CATCH_UP", "PENDING", "alice", null, null, "0 minute");
    String controlNo = insertEligibleApproval(tenant);

    List<PendingApprovalNotification> pending = mapper.findPending(10, 50);

    assertThat(pending)
        .extracting(PendingApprovalNotification::getApprovalNo)
        .contains(controlNo)
        .doesNotContain(no);
  }

  @Test
  @DisplayName("缺少申请人的单: 不出现在待通知列表中")
  void shouldExcludeApprovals_whenRequesterMissing() {
    String tenant = "t-papp-" + BatchDateTimeSupport.utcEpochMillis();
    String no = insertApproval(tenant, "COMPENSATION", "APPROVED", null, "bob", "ok", "0 minute");
    String controlNo = insertEligibleApproval(tenant);

    List<PendingApprovalNotification> pending = mapper.findPending(10, 50);

    assertThat(pending)
        .extracting(PendingApprovalNotification::getApprovalNo)
        .contains(controlNo)
        .doesNotContain(no);
  }

  @Test
  @DisplayName("审批时间超出回溯窗口的单: 不出现在待通知列表中")
  void shouldExcludeApprovals_whenOutsideLookbackWindow() {
    String tenant = "t-papp-" + BatchDateTimeSupport.utcEpochMillis();
    String no = insertApproval(tenant, "DOWNLOAD", "REJECTED", "alice", "bob", "no", "30 minute");
    String controlNo = insertEligibleApproval(tenant);

    List<PendingApprovalNotification> pending = mapper.findPending(10, 50);

    assertThat(pending)
        .extracting(PendingApprovalNotification::getApprovalNo)
        .contains(controlNo)
        .doesNotContain(no);
  }

  @Test
  @DisplayName("已登记通知的单: 不出现在待通知列表中")
  void shouldExcludeApprovals_whenAlreadyNotified() {
    String tenant = "t-papp-" + BatchDateTimeSupport.utcEpochMillis();
    String no = insertApproval(tenant, "CATCH_UP", "EXECUTED", "alice", "bob", "done", "0 minute");
    ConsolePushApprovalNotificationEntity n = new ConsolePushApprovalNotificationEntity();
    n.setTenantId(tenant);
    n.setApprovalNo(no);
    mapper.insertIgnore(n);
    String controlNo = insertEligibleApproval(tenant);

    List<PendingApprovalNotification> pending = mapper.findPending(10, 50);

    assertThat(pending)
        .extracting(PendingApprovalNotification::getApprovalNo)
        .contains(controlNo)
        .doesNotContain(no);
  }

  @Test
  @DisplayName("同一审批单重复登记: 首次写入一行,第二次忽略且不影响行数")
  void shouldInsertOnceAndIgnoreConflict_whenSameApprovalNotifiedTwice() {
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

  private String insertEligibleApproval(String tenantId) {
    return insertApproval(
        tenantId, "CATCH_UP", "APPROVED", "control", "approver", "ok", "0 minute");
  }

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
