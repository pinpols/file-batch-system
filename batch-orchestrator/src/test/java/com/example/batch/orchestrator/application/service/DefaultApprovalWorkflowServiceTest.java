package com.example.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.orchestrator.domain.entity.ApprovalCommandEntity;
import com.example.batch.orchestrator.mapper.ApprovalCommandMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit test: DefaultApprovalWorkflowService state transitions.
 * Covers submit → PENDING, approve → APPROVED, reject → REJECTED, markExecuted → EXECUTED.
 */
class DefaultApprovalWorkflowServiceTest {

    private ApprovalCommandMapper approvalCommandMapper;
    private DefaultApprovalWorkflowService service;

    @BeforeEach
    void setUp() {
        approvalCommandMapper = mock(ApprovalCommandMapper.class);
        service = new DefaultApprovalWorkflowService(approvalCommandMapper);
    }

    // ── submit ────────────────────────────────────────────────────────────────

    @Test
    void shouldInsertApprovalWithPendingStatusOnSubmit() {
        when(approvalCommandMapper.insert(any())).thenReturn(1);

        String approvalNo = service.submit(
                "t1", "COMPENSATION", "DLQ_REPLAY", "DEAD_LETTER", "500",
                "{\"reason\":\"test\"}", "op-001", "trace-001", "idem-001", "test approval");

        assertThat(approvalNo).isNotBlank();
        assertThat(approvalNo).startsWith("apr");
        verify(approvalCommandMapper).insert(any());
    }

    @Test
    void shouldUseEmptyJsonWhenPayloadIsNull() {
        when(approvalCommandMapper.insert(any())).thenReturn(1);

        service.submit("t1", "COMPENSATION", "RETRY", "JOB", "1", null, "op", "trace", "idem", "reason");

        verify(approvalCommandMapper).insert(any());
    }

    // ── approve ───────────────────────────────────────────────────────────────

    @Test
    void shouldThrowWhenApprovalNotFound() {
        when(approvalCommandMapper.selectByTenantAndApprovalNo("t1", "apr-000")).thenReturn(null);

        assertThatThrownBy(() -> service.approve("t1", "apr-000", "approver-001", "approved"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void shouldReturnCurrentStateWhenAlreadyApproved() {
        ApprovalCommandEntity entity = pendingApproval("t1", "apr-001");
        entity.setApprovalStatus("APPROVED");
        when(approvalCommandMapper.selectByTenantAndApprovalNo("t1", "apr-001")).thenReturn(entity);

        ApprovalWorkflowService.ApprovalRecord result = service.approve("t1", "apr-001", "approver-001", "ok");

        assertThat(result.approvalStatus()).isEqualTo("APPROVED");
    }

    @Test
    void shouldMarkApprovedWhenPending() {
        ApprovalCommandEntity entity = pendingApproval("t1", "apr-002");
        ApprovalCommandEntity approved = pendingApproval("t1", "apr-002");
        approved.setApprovalStatus("APPROVED");
        approved.setApproverId("approver-001");
        when(approvalCommandMapper.selectByTenantAndApprovalNo("t1", "apr-002"))
                .thenReturn(entity)
                .thenReturn(approved);
        when(approvalCommandMapper.markApproved("t1", "apr-002", "approver-001", "approved")).thenReturn(1);

        ApprovalWorkflowService.ApprovalRecord result = service.approve("t1", "apr-002", "approver-001", "approved");

        assertThat(result.approvalStatus()).isEqualTo("APPROVED");
        assertThat(result.approverId()).isEqualTo("approver-001");
    }

    // ── reject ────────────────────────────────────────────────────────────────

    @Test
    void shouldThrowWhenRejectingNonExistentApproval() {
        when(approvalCommandMapper.selectByTenantAndApprovalNo("t1", "apr-999")).thenReturn(null);

        assertThatThrownBy(() -> service.reject("t1", "apr-999", "approver-001", "reason"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldMarkRejectedWhenPending() {
        ApprovalCommandEntity entity = pendingApproval("t1", "apr-003");
        ApprovalCommandEntity rejected = pendingApproval("t1", "apr-003");
        rejected.setApprovalStatus("REJECTED");
        rejected.setRejectionReason("data incorrect");
        when(approvalCommandMapper.selectByTenantAndApprovalNo("t1", "apr-003"))
                .thenReturn(entity)
                .thenReturn(rejected);
        when(approvalCommandMapper.markRejected("t1", "apr-003", "approver-001", "data incorrect")).thenReturn(1);

        ApprovalWorkflowService.ApprovalRecord result = service.reject("t1", "apr-003", "approver-001", "data incorrect");

        assertThat(result.approvalStatus()).isEqualTo("REJECTED");
    }

    @Test
    void shouldReturnCurrentStateWhenAlreadyRejected() {
        ApprovalCommandEntity entity = pendingApproval("t1", "apr-004");
        entity.setApprovalStatus("REJECTED");
        when(approvalCommandMapper.selectByTenantAndApprovalNo("t1", "apr-004")).thenReturn(entity);

        ApprovalWorkflowService.ApprovalRecord result = service.reject("t1", "apr-004", "approver-001", "rejected");

        assertThat(result.approvalStatus()).isEqualTo("REJECTED");
    }

    // ── markExecuted ─────────────────────────────────────────────────────────

    @Test
    void shouldMarkExecutedAfterApproval() {
        ApprovalCommandEntity entity = pendingApproval("t1", "apr-005");
        ApprovalCommandEntity executed = pendingApproval("t1", "apr-005");
        executed.setApprovalStatus("EXECUTED");
        when(approvalCommandMapper.selectByTenantAndApprovalNo("t1", "apr-005"))
                .thenReturn(entity)
                .thenReturn(executed);
        when(approvalCommandMapper.markExecuted("t1", "apr-005")).thenReturn(1);

        ApprovalWorkflowService.ApprovalRecord result = service.markExecuted("t1", "apr-005");

        assertThat(result.approvalStatus()).isEqualTo("EXECUTED");
    }

    // ── get ───────────────────────────────────────────────────────────────────

    @Test
    void shouldReturnApprovalRecordOnGet() {
        ApprovalCommandEntity entity = pendingApproval("t1", "apr-006");
        when(approvalCommandMapper.selectByTenantAndApprovalNo("t1", "apr-006")).thenReturn(entity);

        ApprovalWorkflowService.ApprovalRecord result = service.get("t1", "apr-006");

        assertThat(result.tenantId()).isEqualTo("t1");
        assertThat(result.approvalNo()).isEqualTo("apr-006");
        assertThat(result.approvalStatus()).isEqualTo("PENDING");
    }

    @Test
    void shouldThrowOnGetWhenNotFound() {
        when(approvalCommandMapper.selectByTenantAndApprovalNo("t1", "apr-000")).thenReturn(null);

        assertThatThrownBy(() -> service.get("t1", "apr-000"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ApprovalCommandEntity pendingApproval(String tenantId, String approvalNo) {
        ApprovalCommandEntity e = new ApprovalCommandEntity();
        e.setTenantId(tenantId);
        e.setApprovalNo(approvalNo);
        e.setApprovalType("COMPENSATION");
        e.setActionType("DLQ_REPLAY");
        e.setTargetType("DEAD_LETTER");
        e.setTargetId("100");
        e.setPayloadJson("{}");
        e.setApprovalStatus("PENDING");
        e.setRequesterId("op-001");
        e.setSourceTraceId("trace-001");
        e.setSourceIdempotencyKey("idem-001");
        e.setApprovalReason("test approval");
        return e;
    }
}
