package io.github.pinpols.batch.console.domain.ops.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.application.realtime.ConsoleRealtimeEventPort;
import io.github.pinpols.batch.console.domain.job.infrastructure.DefaultConsoleJobRecoveryService;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient;
import io.github.pinpols.batch.console.shared.client.OrchestratorInternalRestClient;
import io.github.pinpols.batch.console.shared.client.TriggerInternalRestClient;
import io.github.pinpols.batch.console.shared.command.CompensationCommandRequest;
import io.github.pinpols.batch.console.shared.command.DeadLetterReplayRequest;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.support.web.ConsoleRequestContextFilter;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
@DisplayName("作业恢复经真实运维端口与审批客户端绑定动作、目标和执行载荷")
class ConsoleRecoveryApprovalBindingTest {

  @Mock
  private OrchestratorInternalRestClient orchestrator;

  @Mock
  private TriggerInternalRestClient trigger;

  @Mock
  private TenantIdResolver tenantGuard;

  @Mock
  private ConsoleRealtimeEventPort events;

  private MockRestServiceServer server;
  private DefaultConsoleJobRecoveryService service;
  private final AtomicInteger compensationRequests = new AtomicInteger();
  private final AtomicReference<JsonNode> sentCompensation = new AtomicReference<>();

  @BeforeEach
  void wireProductionServices() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://approval.test");
    server = MockRestServiceServer.bindTo(builder).build();
    when(orchestrator.client()).thenReturn(builder.build());
    when(tenantGuard.resolveTenant("t1")).thenReturn("t1");
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(
        ConsoleRequestContextFilter.REQUEST_METADATA_ATTRIBUTE,
        new ConsoleRequestMetadata(
            "req-binding", "trace-binding", "t1", "authenticated-checker", null, null));
    ConsoleRequestMetadataResolver metadata = new ConsoleRequestMetadataResolver(request);
    OrchestratorApprovalClient approval = new OrchestratorApprovalClient(orchestrator, metadata);
    service = new DefaultConsoleJobRecoveryService(
        new ConsoleJobOpsSupport(orchestrator, trigger, approval, metadata, tenantGuard, events));
  }

  @ParameterizedTest
  @EnumSource(Mutation.class)
  @DisplayName("已批准载荷的实例、业务日、Workflow 根或节点和策略变化时，拒绝且不发送补偿")
  void shouldRejectChangedExecution_whenApprovalPayloadIsFrozen(Mutation mutation) {
    CompensationCommandRequest approved = request();
    stubApproval("COMPENSATION", JsonUtils.toJson(approved));
    CompensationCommandRequest execution = request();
    execution.setApprovalId("APR-1");
    switch (mutation) {
      case INSTANCE -> execution.setTargetId(202L);
      case BIZ_DATE -> execution.setBizDate("2026-10-23");
      case WORKFLOW_RUN -> execution.setSourceWorkflowRunId(999L);
      case WORKFLOW_NODE -> execution.setSourceWorkflowNodeRunId(888L);
      case STRATEGY -> execution.setStrategy("FORCE");
    }

    assertThatThrownBy(() -> service.compensation(execution, "execution-key"))
        .isInstanceOfSatisfying(
            BizException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.FORBIDDEN));
    assertThat(compensationRequests).hasValue(0);
    server.verify();
  }

  @Test
  @DisplayName("其它动作的已批准单不能授权相同载荷的补偿")
  void shouldRejectDifferentAction_whenTargetAndPayloadMatch() {
    stubApproval("DOWNLOAD", JsonUtils.toJson(request()));
    CompensationCommandRequest execution = request();
    execution.setApprovalId("APR-1");

    assertThatThrownBy(() -> service.compensation(execution, "execution-key"))
        .isInstanceOfSatisfying(
            BizException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.FORBIDDEN));
    assertThat(compensationRequests).hasValue(0);
    server.verify();
  }

  @Test
  @DisplayName("DLQ 审批的外部动作核实结论与证据不能在执行时替换")
  void shouldRejectChangedEvidence_whenDeadLetterApprovalIsFrozen() {
    DeadLetterReplayRequest approved = new DeadLetterReplayRequest();
    approved.setTenantId("t1");
    approved.setDeadLetterId(101L);
    approved.setReason("reconcile selected dead letter");
    approved.setExternalActionVerifiedNotExecuted(false);
    stubApproval("DLQ_REPLAY", JsonUtils.toJson(approved));
    approved.setApprovalId("APR-1");
    approved.setExternalActionVerifiedNotExecuted(true);
    approved.setExternalOutcomeEvidenceReference("incident://unapproved-evidence");

    assertThatThrownBy(() -> service.replayDeadLetter(approved, "execution-key"))
        .isInstanceOfSatisfying(
            BizException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.FORBIDDEN));
    assertThat(compensationRequests).hasValue(0);
    server.verify();
  }

  @Test
  @DisplayName("原始批准载荷允许回填审批号和认证操作者，且仅发送一次补偿")
  void shouldExecuteOriginalPayload_whenOnlyAuthenticatedMetadataIsAdded() {
    stubApproval("COMPENSATION", JsonUtils.toJson(request()));
    CompensationCommandRequest execution = request();
    execution.setApprovalId("APR-1");
    execution.setOperatorId("spoofed-request-operator");

    assertThat(service.compensation(execution, "execution-key")).isEqualTo("CMD-1");
    assertThat(compensationRequests).hasValue(1);
    assertThat(sentCompensation.get().path("operatorId").asText())
        .isEqualTo("authenticated-checker");
    assertThat(sentCompensation.get().path("targetId").asLong()).isEqualTo(101L);
    server.verify();
  }

  @Test
  @DisplayName("自助审批的 Map 载荷缺省可选字段仍可按类型还原，不能误拒绝合法补偿")
  void shouldAcceptTypedPayload_whenOptionalFieldsWereOmittedAtSubmission() {
    CompensationCommandRequest execution = request();
    Map<String, Object> submittedFields = JsonUtils.toMap(execution);
    submittedFields.values().removeIf(value -> value == null);
    stubApproval("COMPENSATION", JsonUtils.toJson(submittedFields));
    execution.setApprovalId("APR-1");

    assertThat(service.compensation(execution, "execution-key")).isEqualTo("CMD-1");
    assertThat(compensationRequests).hasValue(1);
    server.verify();
  }

  private void stubApproval(String action, String payload) {
    String record = JsonUtils.toJson(Map.of(
        "record",
        Map.of(
            "approvalStatus",
            "APPROVED",
            "actionType",
            action,
            "targetType",
            "JOB",
            "targetId",
            "101",
            "payloadJson",
            payload)));
    server
        .expect(requestTo("http://approval.test/internal/approvals/APR-1?tenantId=t1"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(record, MediaType.APPLICATION_JSON));
    server
        .expect(
            ExpectedCount.between(0, 1), requestTo("http://approval.test/internal/compensations"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(request -> {
          compensationRequests.incrementAndGet();
          String body = ((MockClientHttpRequest) request).getBodyAsString();
          sentCompensation.set(JsonUtils.fromJsonStrict(body, JsonNode.class));
          return withSuccess("{\"commandNo\":\"CMD-1\"}", MediaType.APPLICATION_JSON)
              .createResponse(request);
        });
  }

  private CompensationCommandRequest request() {
    CompensationCommandRequest request = new CompensationCommandRequest();
    request.setTenantId("t1");
    request.setCompensationType("JOB");
    request.setTargetId(101L);
    request.setJobCode("DAILY_JOB");
    request.setBizDate("2026-10-22");
    request.setReason("reconcile selected instance");
    request.setSourceWorkflowRunId(11L);
    request.setSourceWorkflowNodeRunId(12L);
    return request;
  }

  private enum Mutation {
    INSTANCE,
    BIZ_DATE,
    WORKFLOW_RUN,
    WORKFLOW_NODE,
    STRATEGY
  }
}
