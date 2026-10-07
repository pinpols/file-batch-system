package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.governance.ApprovalWorkflowService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 集成测试：ApprovalWorkflowService 状态机在真实数据库上的验证。 覆盖：submit → PENDING、approve → APPROVED、reject →
 * REJECTED、markExecuted → EXECUTED。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("审批流程服务在真实数据库上的状态流转,验证提交后的待审批落库以及批准,驳回,执行的状态迁移与重复操作幂等")
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
              approvalType, actionType, targetType, targetId, payloadJson),
          new ApprovalWorkflowService.ApprovalSource(
              requesterId, sourceTraceId, sourceIdempotencyKey, approvalReason));
    }
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired
  private ApprovalWorkflowService approvalWorkflowService;

  @Test
  @DisplayName("提交审批后生成非空审批单号,记录处于待审批状态并保留租户与审批类型")
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

    ApprovalWorkflowService.ApprovalRecord approvalRecord =
        approvalWorkflowService.get("t1", approvalNo);
    assertThat(approvalRecord.approvalStatus()).isEqualTo("PENDING");
    assertThat(approvalRecord.tenantId()).isEqualTo("t1");
    assertThat(approvalRecord.approvalType()).isEqualTo("COMPENSATION");
  }

  @Test
  @DisplayName("待审批记录经批准后状态变为已批准,并记录实际审批人")
  void shouldTransitionFromPendingToApproved() {
    String approvalNo = approvalWorkflowService.submit(new ApprovalSubmissionSpec()
        .actionType("RETRY")
        .targetType("JOB_PARTITION")
        .targetId("200")
        .sourceTraceId("trace-approve")
        .sourceIdempotencyKey("idem-approve")
        .approvalReason("retry needed")
        .build());

    ApprovalWorkflowService.ApprovalRecord approved =
        approvalWorkflowService.approve("t1", approvalNo, "approver-001", "looks good");

    assertThat(approved.approvalStatus()).isEqualTo("APPROVED");
    assertThat(approved.approverId()).isEqualTo("approver-001");
  }

  @Test
  @DisplayName("待审批记录经驳回后状态变为已驳回,不再进入已批准分支")
  void shouldTransitionFromPendingToRejected() {
    String approvalNo = approvalWorkflowService.submit(new ApprovalSubmissionSpec()
        .actionType("DLQ_REPLAY")
        .targetType("DEAD_LETTER")
        .targetId("300")
        .sourceTraceId("trace-reject")
        .sourceIdempotencyKey("idem-reject")
        .approvalReason("suspicious operation")
        .build());

    ApprovalWorkflowService.ApprovalRecord rejected =
        approvalWorkflowService.reject("t1", approvalNo, "approver-002", "data looks wrong");

    assertThat(rejected.approvalStatus()).isEqualTo("REJECTED");
  }

  @Test
  @DisplayName("已批准记录标记执行后状态变为已执行,完成审批闭环")
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

    ApprovalWorkflowService.ApprovalRecord executed =
        approvalWorkflowService.markExecuted("t1", approvalNo);

    assertThat(executed.approvalStatus()).isEqualTo("EXECUTED");
  }

  @Test
  @DisplayName("对已批准记录重复批准时保持幂等,仍返回已批准状态而不产生二次迁移")
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
  @DisplayName("查询不存在的审批单号时抛出业务异常,错误信息提示审批记录不存在")
  void shouldThrowWhenGettingNonExistentApproval() {
    assertThatThrownBy(() -> approvalWorkflowService.get(
            "t1", "apr-nonexistent-" + BatchDateTimeSupport.utcEpochMillis()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.approval.not_found");
  }

  @Test
  @DisplayName("审批单在提交到查询的链路上原样保留业务载荷,读取到的结构化内容与提交时一致")
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

    ApprovalWorkflowService.ApprovalRecord approvalRecord =
        approvalWorkflowService.get("t1", approvalNo);
    assertThat(JSON.readTree(approvalRecord.payloadJson())).isEqualTo(JSON.readTree(payload));
  }
}
