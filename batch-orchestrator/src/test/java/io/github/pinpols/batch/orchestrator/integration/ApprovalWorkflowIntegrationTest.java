package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.governance.ApprovalWorkflowService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
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
  @DisplayName("审批或驳回后，同一原请求仍返回原审批号，变更原原因仍发生冲突")
  void shouldPreserveSubmissionIdentityAfterDecision() {
    for (boolean approve : List.of(true, false)) {
      String key = "identity-after-decision-" + UUID.randomUUID();
      var submission = new ApprovalSubmissionSpec()
          .actionType("CATCH_UP")
          .targetType("CATCH_UP")
          .payloadJson("{\"jobCode\":\"job-a\"}")
          .sourceIdempotencyKey(key)
          .approvalReason("original reason");
      String approvalNo = approvalWorkflowService.submit(submission.build());
      if (approve) {
        approvalWorkflowService.approve("t1", approvalNo, "approver", "decision reason");
      } else {
        approvalWorkflowService.reject("t1", approvalNo, "approver", "decision reason");
      }
      var found = approvalWorkflowService.getBySourceKey("t1", key);
      assertThat(found.sourceApprovalReason()).isEqualTo("original reason");
      assertThat(found.approvalReason()).isEqualTo("decision reason");
      assertThat(approvalWorkflowService.submit(submission.build())).isEqualTo(approvalNo);
      assertThatThrownBy(() -> approvalWorkflowService.submit(
              submission.approvalReason("different original reason").build()))
          .isInstanceOf(BizException.class)
          .hasMessageContaining("error.approval.idempotency_conflict");
    }
  }

  @Test
  @DisplayName("按原请求键查询审批时保留租户隔离并区分未找到")
  void shouldFindApprovalBySourceKeyWithinTenant() {
    String key = "lookup-" + UUID.randomUUID();
    ApprovalSubmissionSpec first = new ApprovalSubmissionSpec()
        .actionType("DLQ_REPLAY")
        .targetType("DEAD_LETTER")
        .targetId("100")
        .payloadJson("{\"reason\":\"first\"}")
        .sourceIdempotencyKey(key);
    ApprovalSubmissionSpec second = new ApprovalSubmissionSpec()
        .actionType("DLQ_REPLAY")
        .targetType("DEAD_LETTER")
        .targetId("200")
        .payloadJson("{\"reason\":\"second\"}")
        .sourceIdempotencyKey(key);
    second.tenantId = "t2";
    String firstNo = approvalWorkflowService.submit(first.build());
    String secondNo = approvalWorkflowService.submit(second.build());

    assertThat(approvalWorkflowService.getBySourceKey("t1", key).approvalNo()).isEqualTo(firstNo);
    assertThat(approvalWorkflowService.getBySourceKey("t2", key).approvalNo()).isEqualTo(secondNo);
    assertThatThrownBy(() -> approvalWorkflowService.getBySourceKey("t3", key))
        .isInstanceOfSatisfying(
            BizException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.NOT_FOUND));
    assertThatThrownBy(() -> approvalWorkflowService.getBySourceKey("t1", " "))
        .isInstanceOfSatisfying(
            BizException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.INVALID_ARGUMENT));
  }

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
  @DisplayName("并发安全的来源幂等键重试返回同一审批单且不重复落库")
  void shouldReuseApprovalForRepeatedSourceIdempotencyKey() {
    String key = "workflow-replay-" + UUID.randomUUID();
    ApprovalSubmissionSpec submission = new ApprovalSubmissionSpec()
        .actionType("RETRY")
        .targetType("JOB_PARTITION")
        .targetId("idempotent-target")
        .payloadJson("{\"value\":1,\"nested\":{\"x\":true}}")
        .sourceIdempotencyKey(key)
        .approvalReason("same replay request");

    String firstApprovalNo = approvalWorkflowService.submit(submission.build());
    String retriedApprovalNo = approvalWorkflowService.submit(new ApprovalSubmissionSpec()
        .actionType("RETRY")
        .targetType("JOB_PARTITION")
        .targetId("idempotent-target")
        .payloadJson("{\"nested\":{\"x\":true},\"value\":1}")
        .sourceIdempotencyKey(key)
        .approvalReason("same replay request")
        .build());

    assertThat(retriedApprovalNo).isEqualTo(firstApprovalNo);
    assertThatThrownBy(() -> approvalWorkflowService.submit(new ApprovalSubmissionSpec()
            .actionType("RETRY")
            .targetType("JOB_PARTITION")
            .targetId("different-target")
            .payloadJson("{\"value\":1}")
            .sourceIdempotencyKey(key)
            .approvalReason("same replay request")
            .build()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.approval.idempotency_conflict");
  }

  @Test
  @DisplayName("批量日长作业编码的稳定审批键与载荷在真实数据库中支持重试")
  void shouldReuseBatchDayApprovalForLongJobCode() throws Exception {
    String key = "batchday-8c32e74419490ac8347867a8dca66011689fdc4aff0230666cc318943bec9387";
    String payload = """
        {"tenantId":"tenant-a","requestId":null,"workflowDefinitionId":null,
         "jobCode":"%s","bizDate":"2026-10-14","batchDayCalendarCode":"CAL",
         "scheduledAt":null,"reason":null,"approvalId":null}
        """.formatted("J".repeat(128));
    ApprovalWorkflowService.ApprovalSubmitCommand command =
        ApprovalWorkflowService.ApprovalSubmitCommand.of(
            "tenant-a",
            new ApprovalWorkflowService.ApprovalTarget(
                "CATCH_UP", "CATCH_UP", "CATCH_UP", null, payload),
            new ApprovalWorkflowService.ApprovalSource("op-001", "trace-1", key, null));

    String approvalNo = approvalWorkflowService.submit(command);
    assertThat(approvalWorkflowService.submit(command)).isEqualTo(approvalNo);
    ApprovalWorkflowService.ApprovalRecord saved =
        approvalWorkflowService.get("tenant-a", approvalNo);
    assertThat(saved.sourceIdempotencyKey()).isEqualTo(key);
    assertThat(saved.approvalStatus()).isEqualTo("PENDING");
    assertThat(JSON.readTree(saved.payloadJson())).isEqualTo(JSON.readTree(payload));
  }

  @Test
  @DisplayName("并发提交同一幂等审批只返回一个审批单号")
  void shouldCoalesceConcurrentSubmissionsForSourceIdempotencyKey() throws Exception {
    String key = "workflow-replay-concurrent-" + UUID.randomUUID();
    ApprovalWorkflowService.ApprovalSubmitCommand command = new ApprovalSubmissionSpec()
        .actionType("RETRY")
        .targetType("JOB_PARTITION")
        .targetId("concurrent-target")
        .payloadJson("{\"value\":1}")
        .sourceIdempotencyKey(key)
        .approvalReason("concurrent replay request")
        .build();
    int submitterCount = 6;
    CountDownLatch startGate = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(submitterCount);
    try {
      List<Future<String>> submissions = IntStream.range(0, submitterCount)
          .mapToObj(index -> executor.submit(() -> {
            startGate.await();
            return approvalWorkflowService.submit(command);
          }))
          .toList();
      startGate.countDown();

      List<String> approvalNumbers =
          submissions.stream().map(future -> getApprovalNo(future)).toList();

      assertThat(approvalNumbers).hasSize(submitterCount).doesNotContainNull();
      assertThat(approvalNumbers).containsOnly(approvalNumbers.getFirst());
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private String getApprovalNo(Future<String> submission) {
    try {
      return submission.get(10, TimeUnit.SECONDS);
    } catch (Exception exception) {
      throw new AssertionError("Concurrent approval submission did not finish", exception);
    }
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
