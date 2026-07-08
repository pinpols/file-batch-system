package io.github.pinpols.batch.console.domain.ops.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.ops.infrastructure.OrchestratorApprovalClient.ApprovalRecord;
import io.github.pinpols.batch.console.domain.ops.infrastructure.OrchestratorApprovalClient.ApprovalRecordResponse;
import io.github.pinpols.batch.console.domain.ops.infrastructure.OrchestratorApprovalClient.ApprovalSubmitCommand;
import io.github.pinpols.batch.console.domain.ops.infrastructure.OrchestratorApprovalClient.ApprovalSubmitResponse;
import io.github.pinpols.batch.console.domain.ops.infrastructure.OrchestratorApprovalClient.ApprovalTargetBinding;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClient;

/** 共享审批客户端单测：目标绑定、空响应、状态校验、入参清洗。 */
class OrchestratorApprovalClientTest {

  private final OrchestratorInternalRestClient orchestratorInternalRestClient =
      mock(OrchestratorInternalRestClient.class);
  private final ConsoleRequestMetadataResolver metadataResolver =
      mock(ConsoleRequestMetadataResolver.class);

  private OrchestratorApprovalClient client;

  @BeforeEach
  void setUp() {
    client = new OrchestratorApprovalClient(orchestratorInternalRestClient, metadataResolver);
    when(metadataResolver.current())
        .thenReturn(new ConsoleRequestMetadata("req-1", "trace-1", "t1", "op-1", null, null));
  }

  private void stubApprovalRecord(String status, String targetType, String targetId) {
    RestClient restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
    when(orchestratorInternalRestClient.build()).thenReturn(restClient);
    when(restClient
            .get()
            .uri(anyString(), any(Object[].class))
            .retrieve()
            .body(ApprovalRecordResponse.class))
        .thenReturn(new ApprovalRecordResponse(new ApprovalRecord(status, targetType, targetId)));
  }

  private RestClient.RequestBodySpec stubSubmit(ApprovalSubmitResponse response) {
    RestClient restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
    RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
    RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
    RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
    when(orchestratorInternalRestClient.build()).thenReturn(restClient);
    when(restClient.post()).thenReturn(uriSpec);
    when(uriSpec.uri(anyString())).thenReturn(bodySpec);
    when(bodySpec.header(anyString(), any(String[].class))).thenReturn(bodySpec);
    when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
    when(bodySpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(ApprovalSubmitResponse.class)).thenReturn(response);
    return bodySpec;
  }

  // ── requireApprovedApproval：目标绑定 ──────────────────────────────────

  @Test
  void shouldPass_whenApprovedAndTargetMatches() {
    stubApprovalRecord("APPROVED", "FILE", "100");
    assertThatCode(
            () -> client.requireApprovedApproval("t1", "AP-1", ApprovalTargetBinding.file(100L)))
        .doesNotThrowAnyException();
  }

  @Test
  void shouldPass_whenExecutedTreatedAsApproved() {
    stubApprovalRecord("EXECUTED", "FILE", "100");
    assertThatCode(
            () -> client.requireApprovedApproval("t1", "AP-1", ApprovalTargetBinding.file(100L)))
        .doesNotThrowAnyException();
  }

  @Test
  void shouldRejectForbidden_whenTargetIdMismatch() {
    stubApprovalRecord("APPROVED", "FILE", "200");
    assertThatThrownBy(
            () -> client.requireApprovedApproval("t1", "AP-1", ApprovalTargetBinding.file(100L)))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.FORBIDDEN);
  }

  @Test
  void shouldRejectForbidden_whenTargetTypeMismatch() {
    stubApprovalRecord("APPROVED", "EXPORT", "100");
    assertThatThrownBy(
            () -> client.requireApprovedApproval("t1", "AP-1", ApprovalTargetBinding.file(100L)))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.FORBIDDEN);
  }

  @Test
  void shouldRejectStateConflict_whenNotApprovedYet() {
    stubApprovalRecord("PENDING", "FILE", "100");
    assertThatThrownBy(
            () -> client.requireApprovedApproval("t1", "AP-1", ApprovalTargetBinding.file(100L)))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.STATE_CONFLICT);
  }

  @Test
  void shouldSkipBinding_whenExplicitNone() {
    // none() 是既有作业运维路径的显式过渡声明：仅校验状态,不校验目标。
    stubApprovalRecord("APPROVED", "JOB", "999");
    assertThatCode(() -> client.requireApprovedApproval("t1", "AP-1", ApprovalTargetBinding.none()))
        .doesNotThrowAnyException();
  }

  // ── submitApproval ──────────────────────────────────────────────────────

  @Test
  void shouldReturnApprovalNo_onSuccessfulSubmit() {
    stubSubmit(new ApprovalSubmitResponse("APR-9"));
    String approvalNo =
        client.submitApproval(
            ApprovalSubmitCommand.builder()
                .tenantId("t1")
                .approvalType("DOWNLOAD")
                .actionType("DOWNLOAD")
                .targetType("FILE")
                .targetId("100")
                .idempotencyKey("idem-1")
                .build());
    assertThat(approvalNo).isEqualTo("APR-9");
  }

  @Test
  void shouldThrowDefaultKey_whenSubmitResponseEmpty() {
    stubSubmit(null);
    assertThatThrownBy(
            () ->
                client.submitApproval(
                    ApprovalSubmitCommand.builder().tenantId("t1").idempotencyKey("i").build()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.approval.empty_response");
  }

  @Test
  void shouldThrowCallerKey_whenSubmitResponseEmptyAndCustomKeyGiven() {
    stubSubmit(new ApprovalSubmitResponse("  "));
    assertThatThrownBy(
            () ->
                client.submitApproval(
                    ApprovalSubmitCommand.builder()
                        .tenantId("t1")
                        .idempotencyKey("i")
                        .emptyResponseMessageKey("error.approval.submit_failed")
                        .build()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.approval.submit_failed");
  }

  @Test
  void shouldSanitizeRequesterIdAndReason() {
    RestClient.RequestBodySpec bodySpec = stubSubmit(new ApprovalSubmitResponse("APR-10"));
    client.submitApproval(
        ApprovalSubmitCommand.builder()
            .tenantId("t1")
            .approvalType("SELF_SERVICE")
            .actionType("RERUN")
            .targetType("JOB_INSTANCE")
            .targetId("JOB-1")
            .requesterId("  ops\u0007-user  ")
            .approvalReason("  reason\u0000with-ctrl  ")
            .idempotencyKey("idem-2")
            .build());
    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(bodySpec).body(captor.capture());
    String json = JsonUtils.toJson(captor.getValue());
    assertThat(json).contains("\"requesterId\":\"ops-user\"");
    assertThat(json).contains("\"approvalReason\":\"reasonwith-ctrl\"");
  }

  @Test
  void shouldFallbackRequesterIdToOperator_whenNotProvided() {
    RestClient.RequestBodySpec bodySpec = stubSubmit(new ApprovalSubmitResponse("APR-11"));
    client.submitApproval(
        ApprovalSubmitCommand.builder().tenantId("t1").idempotencyKey("idem-3").build());
    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(bodySpec).body(captor.capture());
    String json = JsonUtils.toJson(captor.getValue());
    assertThat(json).contains("\"requesterId\":\"op-1\"");
    assertThat(json).contains("\"sourceTraceId\":\"trace-1\"");
    assertThat(json).contains("\"sourceIdempotencyKey\":\"idem-3\"");
  }
}
