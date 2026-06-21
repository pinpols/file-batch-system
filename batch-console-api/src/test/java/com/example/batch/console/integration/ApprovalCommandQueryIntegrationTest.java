package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.model.PageRequest;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.BatchConsoleApiApplication;
import com.example.batch.console.domain.ops.mapper.ApprovalCommandMapper;
import com.example.batch.console.domain.ops.query.ApprovalCommandQuery;
import com.example.batch.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ApprovalCommandQueryIntegrationTest extends AbstractIntegrationTest {

  @Autowired private ApprovalCommandMapper approvalCommandMapper;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void shouldReturnEmptyWhenNoApprovalsExist() {
    ApprovalCommandQuery query = new ApprovalCommandQuery();
    query.setTenantId("no-such-tenant-" + BatchDateTimeSupport.utcEpochMillis());
    query.setPageRequest(new PageRequest(1, 10));
    assertThat(approvalCommandMapper.selectByQuery(query)).isEmpty();
  }

  @Test
  void shouldQueryApprovalsByStatusAndCount() {
    String tenantId = "t-approval-" + BatchDateTimeSupport.utcEpochMillis();
    insertApproval(tenantId, "apr-1", "COMPENSATION", "COMPENSATION", "PENDING");
    insertApproval(tenantId, "apr-2", "DOWNLOAD", "DOWNLOAD", "EXECUTED");

    ApprovalCommandQuery query = new ApprovalCommandQuery();
    query.setTenantId(tenantId);
    query.setApprovalStatus("PENDING");
    query.setPageRequest(new PageRequest(1, 10));

    assertThat(approvalCommandMapper.selectByQuery(query)).hasSize(1);
    assertThat(approvalCommandMapper.countByStatus(tenantId, "PENDING")).isEqualTo(1);
  }

  @Test
  void shouldQueryApprovalsByRequesterId() {
    String tenantId = "t-approval-req-" + BatchDateTimeSupport.utcEpochMillis();
    insertApprovalRich(tenantId, "apr-r1", "alice", "JOB", "100");
    insertApprovalRich(tenantId, "apr-r2", "bob", "JOB", "101");

    ApprovalCommandQuery query = new ApprovalCommandQuery();
    query.setTenantId(tenantId);
    query.setRequesterId("alice");
    query.setPageRequest(new PageRequest(1, 10));

    assertThat(approvalCommandMapper.selectByQuery(query)).hasSize(1);
    assertThat(approvalCommandMapper.countByQuery(query)).isEqualTo(1);
  }

  @Test
  void shouldQueryApprovalsByKeywordAcrossColumns() {
    String tenantId = "t-approval-kw-" + BatchDateTimeSupport.utcEpochMillis();
    insertApprovalRich(tenantId, "PAYROLL-001", "alice", "JOB", "100");
    insertApprovalRich(tenantId, "apr-x", "bob", "PAYROLL_BATCH", "200");
    insertApprovalRich(tenantId, "apr-y", "carol", "WORKFLOW", "300");

    ApprovalCommandQuery query = new ApprovalCommandQuery();
    query.setTenantId(tenantId);
    query.setKeyword("payroll");
    query.setPageRequest(new PageRequest(1, 10));

    // 命中 approval_no(PAYROLL-001)与 target_type(PAYROLL_BATCH)两行,大小写不敏感
    assertThat(approvalCommandMapper.selectByQuery(query)).hasSize(2);
    assertThat(approvalCommandMapper.countByQuery(query)).isEqualTo(2);
  }

  private void insertApproval(
      String tenantId, String approvalNo, String approvalType, String actionType, String status) {
    jdbcTemplate.update(
        """
        INSERT INTO batch.approval_command
          (tenant_id, approval_no, approval_type, action_type, target_type, target_id, payload_json,
           approval_status, requester_id, source_trace_id, source_idempotency_key, approval_reason,
           created_at, updated_at)
        VALUES (?, ?, ?, ?, 'JOB', '1', '{}'::jsonb,
                ?, 'u1', 'trace-1', 'idem-1', 'reason',
                now(), now())
        """,
        tenantId,
        approvalNo,
        approvalType,
        actionType,
        status);
  }

  private void insertApprovalRich(
      String tenantId, String approvalNo, String requesterId, String targetType, String targetId) {
    jdbcTemplate.update(
        """
        INSERT INTO batch.approval_command
          (tenant_id, approval_no, approval_type, action_type, target_type, target_id, payload_json,
           approval_status, requester_id, source_trace_id, source_idempotency_key, approval_reason,
           created_at, updated_at)
        VALUES (?, ?, 'DOWNLOAD', 'DOWNLOAD', ?, ?, '{}'::jsonb,
                'PENDING', ?, ?, ?, 'reason',
                now(), now())
        """,
        tenantId,
        approvalNo,
        targetType,
        targetId,
        requesterId,
        "trace-" + approvalNo,
        "idem-" + approvalNo);
  }
}
