package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.console.BatchConsoleApiApplication;
import com.example.batch.console.domain.query.ApprovalCommandQuery;
import com.example.batch.console.mapper.ApprovalCommandMapper;
import com.example.batch.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
        classes = BatchConsoleApiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ApprovalCommandQueryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ApprovalCommandMapper approvalCommandMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldReturnEmptyWhenNoApprovalsExist() {
        ApprovalCommandQuery query = new ApprovalCommandQuery();
        query.setTenantId("no-such-tenant-" + System.currentTimeMillis());
        query.setLimit(10);
        assertThat(approvalCommandMapper.selectByQuery(query)).isEmpty();
    }

    @Test
    void shouldQueryApprovalsByStatusAndCount() {
        String tenantId = "t-approval-" + System.currentTimeMillis();
        insertApproval(tenantId, "apr-1", "COMPENSATION", "COMPENSATION", "PENDING");
        insertApproval(tenantId, "apr-2", "DOWNLOAD", "DOWNLOAD", "EXECUTED");

        ApprovalCommandQuery query = new ApprovalCommandQuery();
        query.setTenantId(tenantId);
        query.setApprovalStatus("PENDING");
        query.setLimit(10);

        assertThat(approvalCommandMapper.selectByQuery(query)).hasSize(1);
        assertThat(approvalCommandMapper.countByStatus(tenantId, "PENDING")).isEqualTo(1);
    }

    private void insertApproval(String tenantId, String approvalNo, String approvalType, String actionType, String status) {
        jdbcTemplate.update("""
                INSERT INTO batch.approval_command
                  (tenant_id, approval_no, approval_type, action_type, target_type, target_id, payload_json,
                   approval_status, requester_id, source_trace_id, source_idempotency_key, approval_reason,
                   created_at, updated_at)
                VALUES (?, ?, ?, ?, 'JOB', '1', '{}'::jsonb,
                        ?, 'u1', 'trace-1', 'idem-1', 'reason',
                        now(), now())
                """, tenantId, approvalNo, approvalType, actionType, status);
    }
}

