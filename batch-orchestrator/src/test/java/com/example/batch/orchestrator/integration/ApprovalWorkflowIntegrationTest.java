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
 * 集成测试：ApprovalWorkflowService 状态机在真实数据库上的验证。
 * 覆盖：submit → PENDING、approve → APPROVED、reject → REJECTED、markExecuted → EXECUTED。
 */
@SpringBootTest(
        classes = BatchOrchestratorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApprovalWorkflowIntegrationTest extends AbstractIntegrationTest {

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

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private ApprovalWorkflowService approvalWorkflowService;

    @Test
    void shouldSubmitApprovalAndReturnApprovalNo() {
        String approvalNo = approvalWorkflowService.submit(new ApprovalSubmissionSpec()
                .actionType("DLQ_REPLAY")
                .targetType("DEAD_LETTER")
                .targetId("100")
                .payloadJson("{\"reason\":\"data error\"}")
                .sourceTraceId("trace-001")
                .sourceIdempotencyKey("idem-001")
                .approvalReason("need approval")
                .build());

        assertThat(approvalNo).isNotBlank();

        ApprovalWorkflowService.ApprovalRecord record = approvalWorkflowService.get("t1", approvalNo);
        assertThat(record.approvalStatus()).isEqualTo("PENDING");
        assertThat(record.tenantId()).isEqualTo("t1");
        assertThat(record.approvalType()).isEqualTo("COMPENSATION");
    }

    @Test
    void shouldTransitionFromPendingToApproved() {
        String approvalNo = approvalWorkflowService.submit(new ApprovalSubmissionSpec()
                .actionType("RETRY")
                .targetType("JOB_PARTITION")
                .targetId("200")
                .sourceTraceId("trace-approve")
                .sourceIdempotencyKey("idem-approve")
                .approvalReason("retry needed")
                .build());

        ApprovalWorkflowService.ApprovalRecord approved = approvalWorkflowService.approve(
                "t1", approvalNo, "approver-001", "looks good");

        assertThat(approved.approvalStatus()).isEqualTo("APPROVED");
        assertThat(approved.approverId()).isEqualTo("approver-001");
    }

    @Test
    void shouldTransitionFromPendingToRejected() {
        String approvalNo = approvalWorkflowService.submit(new ApprovalSubmissionSpec()
                .actionType("DLQ_REPLAY")
                .targetType("DEAD_LETTER")
                .targetId("300")
                .sourceTraceId("trace-reject")
                .sourceIdempotencyKey("idem-reject")
                .approvalReason("suspicious operation")
                .build());

        ApprovalWorkflowService.ApprovalRecord rejected = approvalWorkflowService.reject(
                "t1", approvalNo, "approver-002", "data looks wrong");

        assertThat(rejected.approvalStatus()).isEqualTo("REJECTED");
    }

    @Test
    void shouldTransitionFromApprovedToExecuted() {
        String approvalNo = approvalWorkflowService.submit(new ApprovalSubmissionSpec()
                .actionType("RETRY")
                .targetType("JOB")
                .targetId("400")
                .sourceTraceId("trace-exec")
                .sourceIdempotencyKey("idem-exec")
                .approvalReason("retry")
                .build());

        approvalWorkflowService.approve("t1", approvalNo, "approver-001", "ok");

        ApprovalWorkflowService.ApprovalRecord executed = approvalWorkflowService.markExecuted("t1", approvalNo);

        assertThat(executed.approvalStatus()).isEqualTo("EXECUTED");
    }

    @Test
    void shouldReturnCurrentStateWhenAlreadyApproved() {
        String approvalNo = approvalWorkflowService.submit(new ApprovalSubmissionSpec()
                .actionType("RETRY")
                .targetType("JOB")
                .targetId("500")
                .sourceTraceId("trace-double-approve")
                .sourceIdempotencyKey("idem-double")
                .approvalReason("retry")
                .build());

        approvalWorkflowService.approve("t1", approvalNo, "approver-001", "first approval");
        // 第二次审批应具有幂等性 —— 返回当前 APPROVED 状态
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
        String approvalNo = approvalWorkflowService.submit(new ApprovalSubmissionSpec()
                .actionType("DLQ_REPLAY")
                .targetType("DEAD_LETTER")
                .targetId("999")
                .payloadJson(payload)
                .sourceTraceId("trace-payload")
                .sourceIdempotencyKey("idem-payload")
                .approvalReason("verify payload")
                .build());

        ApprovalWorkflowService.ApprovalRecord record = approvalWorkflowService.get("t1", approvalNo);
        assertThat(JSON.readTree(record.payloadJson())).isEqualTo(JSON.readTree(payload));
    }

}
