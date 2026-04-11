package com.example.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.ApprovalCommandStatus;
import com.example.batch.orchestrator.domain.entity.ApprovalCommandEntity;
import com.example.batch.orchestrator.mapper.ApprovalCommandMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 单元测试：{@link DefaultApprovalWorkflowService} 状态流转。
 * 覆盖 submit→PENDING、approve→APPROVED、reject→REJECTED、markExecuted→EXECUTED。
 */
class DefaultApprovalWorkflowServiceTest {

    private static final class ApprovalSubmissionSpec {
        private String tenantId = "t1";
        private String approvalType = "COMPENSATION";
        private String actionType;
        private String targetType;
        private String targetId;
        private String payloadJson;
        private String requesterId = "op-001";
        private String sourceTraceId;
        private String sourceIdempotencyKey;
        private String approvalReason;

        private ApprovalSubmissionSpec actionType(String actionType) {
            this.actionType = actionType;
            return this;
        }

        private ApprovalSubmissionSpec targetType(String targetType) {
            this.targetType = targetType;
            return this;
        }

        private ApprovalSubmissionSpec targetId(String targetId) {
            this.targetId = targetId;
            return this;
        }

        private ApprovalSubmissionSpec payloadJson(String payloadJson) {
            this.payloadJson = payloadJson;
            return this;
        }

        private ApprovalSubmissionSpec requesterId(String requesterId) {
            this.requesterId = requesterId;
            return this;
        }

        private ApprovalSubmissionSpec sourceTraceId(String sourceTraceId) {
            this.sourceTraceId = sourceTraceId;
            return this;
        }

        private ApprovalSubmissionSpec sourceIdempotencyKey(String sourceIdempotencyKey) {
            this.sourceIdempotencyKey = sourceIdempotencyKey;
            return this;
        }

        private ApprovalSubmissionSpec approvalReason(String approvalReason) {
            this.approvalReason = approvalReason;
            return this;
        }

        private ApprovalWorkflowService.ApprovalSubmitCommand build() {
            return ApprovalWorkflowService.ApprovalSubmitCommand.of(
                    tenantId,
                    new ApprovalWorkflowService.ApprovalTarget(
                            approvalType,
                            actionType,
                            targetType,
                            targetId,
                            payloadJson
                    ),
                    new ApprovalWorkflowService.ApprovalSource(
                            requesterId,
                            sourceTraceId,
                            sourceIdempotencyKey,
                            approvalReason
                    )
            );
        }
    }

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

        String approvalNo = service.submit(new ApprovalSubmissionSpec()
                .actionType("DLQ_REPLAY")
                .targetType("DEAD_LETTER")
                .targetId("500")
                .payloadJson("{\"reason\":\"test\"}")
                .requesterId("op-001")
                .sourceTraceId("trace-001")
                .sourceIdempotencyKey("idem-001")
                .approvalReason("test approval")
                .build());

        assertThat(approvalNo).isNotBlank();
        assertThat(approvalNo).startsWith("apr");
        verify(approvalCommandMapper).insert(any());
    }

    @Test
    void shouldUseEmptyJsonWhenPayloadIsNull() {
        when(approvalCommandMapper.insert(any())).thenReturn(1);

        service.submit(new ApprovalSubmissionSpec()
                .actionType("RETRY")
                .targetType("JOB")
                .targetId("1")
                .requesterId("op")
                .sourceTraceId("trace")
                .sourceIdempotencyKey("idem")
                .approvalReason("reason")
                .build());

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
        when(approvalCommandMapper.markApproved("t1", "apr-002", "approver-001", "approved",
                ApprovalCommandStatus.APPROVED.code(), ApprovalCommandStatus.PENDING.code())).thenReturn(1);

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
        when(approvalCommandMapper.markRejected("t1", "apr-003", "approver-001", "data incorrect",
                ApprovalCommandStatus.REJECTED.code(), ApprovalCommandStatus.PENDING.code())).thenReturn(1);

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
        ApprovalCommandEntity executed = pendingApproval("t1", "apr-005");
        executed.setApprovalStatus("EXECUTED");
        when(approvalCommandMapper.markExecuted("t1", "apr-005",
                ApprovalCommandStatus.EXECUTED.code(), ApprovalCommandStatus.APPROVED.code())).thenReturn(1);
        when(approvalCommandMapper.selectByTenantAndApprovalNo("t1", "apr-005")).thenReturn(executed);

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
