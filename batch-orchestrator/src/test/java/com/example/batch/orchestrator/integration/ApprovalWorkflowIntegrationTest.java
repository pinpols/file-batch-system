package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.application.service.ApprovalWorkflowService;
import com.example.batch.testing.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration test: ApprovalWorkflowService state machine against real DB.
 * Covers: submit → PENDING, approve → APPROVED, reject → REJECTED, markExecuted → EXECUTED.
 */
@SpringBootTest(
        classes = BatchOrchestratorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApprovalWorkflowIntegrationTest extends AbstractIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private ApprovalWorkflowService approvalWorkflowService;

    @Test
    void shouldSubmitApprovalAndReturnApprovalNo() {
        String approvalNo = approvalWorkflowService.submit(submitCommand(
                "t1", "COMPENSATION", "DLQ_REPLAY", "DEAD_LETTER", "100",
                "{\"reason\":\"data error\"}", "op-001", "trace-001", "idem-001", "need approval"));

        assertThat(approvalNo).isNotBlank();

        ApprovalWorkflowService.ApprovalRecord record = approvalWorkflowService.get("t1", approvalNo);
        assertThat(record.approvalStatus()).isEqualTo("PENDING");
        assertThat(record.tenantId()).isEqualTo("t1");
        assertThat(record.approvalType()).isEqualTo("COMPENSATION");
    }

    @Test
    void shouldTransitionFromPendingToApproved() {
        String approvalNo = approvalWorkflowService.submit(submitCommand(
                "t1", "COMPENSATION", "RETRY", "JOB_PARTITION", "200",
                null, "op-001", "trace-approve", "idem-approve", "retry needed"));

        ApprovalWorkflowService.ApprovalRecord approved = approvalWorkflowService.approve(
                "t1", approvalNo, "approver-001", "looks good");

        assertThat(approved.approvalStatus()).isEqualTo("APPROVED");
        assertThat(approved.approverId()).isEqualTo("approver-001");
    }

    @Test
    void shouldTransitionFromPendingToRejected() {
        String approvalNo = approvalWorkflowService.submit(submitCommand(
                "t1", "COMPENSATION", "DLQ_REPLAY", "DEAD_LETTER", "300",
                null, "op-001", "trace-reject", "idem-reject", "suspicious operation"));

        ApprovalWorkflowService.ApprovalRecord rejected = approvalWorkflowService.reject(
                "t1", approvalNo, "approver-002", "data looks wrong");

        assertThat(rejected.approvalStatus()).isEqualTo("REJECTED");
    }

    @Test
    void shouldTransitionFromApprovedToExecuted() {
        String approvalNo = approvalWorkflowService.submit(submitCommand(
                "t1", "COMPENSATION", "RETRY", "JOB", "400",
                null, "op-001", "trace-exec", "idem-exec", "retry"));

        approvalWorkflowService.approve("t1", approvalNo, "approver-001", "ok");

        ApprovalWorkflowService.ApprovalRecord executed = approvalWorkflowService.markExecuted("t1", approvalNo);

        assertThat(executed.approvalStatus()).isEqualTo("EXECUTED");
    }

    @Test
    void shouldReturnCurrentStateWhenAlreadyApproved() {
        String approvalNo = approvalWorkflowService.submit(submitCommand(
                "t1", "COMPENSATION", "RETRY", "JOB", "500",
                null, "op-001", "trace-double-approve", "idem-double", "retry"));

        approvalWorkflowService.approve("t1", approvalNo, "approver-001", "first approval");
        // Second approve should be idempotent — returns current APPROVED state
        ApprovalWorkflowService.ApprovalRecord result = approvalWorkflowService.approve(
                "t1", approvalNo, "approver-002", "second approval attempt");

        assertThat(result.approvalStatus()).isEqualTo("APPROVED");
    }

    @Test
    void shouldThrowWhenGettingNonExistentApproval() {
        assertThatThrownBy(() -> approvalWorkflowService.get("t1", "apr-nonexistent-" + System.currentTimeMillis()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldPreservePayloadJsonThroughApprovalLifecycle() throws Exception {
        String payload = "{\"deadLetterId\":999,\"reason\":\"retry needed\"}";
        String approvalNo = approvalWorkflowService.submit(submitCommand(
                "t1", "COMPENSATION", "DLQ_REPLAY", "DEAD_LETTER", "999",
                payload, "op-001", "trace-payload", "idem-payload", "verify payload"));

        ApprovalWorkflowService.ApprovalRecord record = approvalWorkflowService.get("t1", approvalNo);
        assertThat(JSON.readTree(record.payloadJson())).isEqualTo(JSON.readTree(payload));
    }

    private static ApprovalWorkflowService.ApprovalSubmitCommand submitCommand(String tenantId,
                                                                               String approvalType,
                                                                               String actionType,
                                                                               String targetType,
                                                                               String targetId,
                                                                               String payloadJson,
                                                                               String requesterId,
                                                                               String sourceTraceId,
                                                                               String sourceIdempotencyKey,
                                                                               String approvalReason) {
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
