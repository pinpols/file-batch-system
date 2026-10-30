package io.github.pinpols.batch.console.shared.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient.ApprovalSubmitCommand;
import io.github.pinpols.batch.console.shared.client.OrchestratorInternalRestClient;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@DisplayName("按原请求键查询审批的客户端契约")
class OrchestratorApprovalSourceLookupTest {

  private MockRestServiceServer server;
  private OrchestratorApprovalClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://orchestrator.test");
    server = MockRestServiceServer.bindTo(builder).build();
    OrchestratorInternalRestClient internal = mock(OrchestratorInternalRestClient.class);
    when(internal.client()).thenReturn(builder.build());
    client = new OrchestratorApprovalClient(internal, mock(ConsoleRequestMetadataResolver.class));
  }

  @Test
  @DisplayName("发送原请求键并校验返回的租户、来源键和审批身份")
  void shouldReturnMatchingApprovalFromSourceLookup() {
    server
        .expect(requestTo("http://orchestrator.test/internal/approvals/by-source-key?tenantId=t1"))
        .andExpect(header("Idempotency-Key", "source-1"))
        .andRespond(withSuccess("""
            {"record":{"tenantId":"t1","approvalNo":"apr-1","sourceIdempotencyKey":"source-1",
             "approvalStatus":"PENDING","targetId":"JOB_A","payloadJson":"{}"}}
            """, MediaType.APPLICATION_JSON));
    var result = client.lookupBySourceKey("t1", "source-1");
    assertThat(result.approvalNo()).isEqualTo("apr-1");
    assertThat(result.targetId()).isEqualTo("JOB_A");
    server.verify();
  }

  @Test
  @DisplayName("未找到与服务不可用保留为不同的查询结果，不自动重发")
  void shouldKeepNotFoundAndUnavailableDistinct() {
    String url = "http://orchestrator.test/internal/approvals/by-source-key?tenantId=t1";
    server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.NOT_FOUND));
    server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    assertThatThrownBy(() -> client.lookupBySourceKey("t1", "source-1"))
        .isInstanceOf(HttpClientErrorException.NotFound.class);
    assertThatThrownBy(() -> client.lookupBySourceKey("t1", "source-1"))
        .isInstanceOf(HttpServerErrorException.ServiceUnavailable.class);
    server.verify();
  }

  @Test
  @DisplayName("对端返回其他租户时拒绝使用记录")
  void shouldRejectMismatchedSourceIdentity() {
    server
        .expect(requestTo("http://orchestrator.test/internal/approvals/by-source-key?tenantId=t1"))
        .andRespond(withSuccess("""
            {"record":{"tenantId":"t2","approvalNo":"apr-1","sourceIdempotencyKey":"source-1"}}
            """, MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> client.lookupBySourceKey("t1", "source-1"))
        .isInstanceOf(BizException.class);
    server.verify();
  }

  @Test
  @DisplayName("来源键不匹配或响应缺记录时拒绝使用结果")
  void shouldRejectMismatchedKeyAndEmptyRecord() {
    String url = "http://orchestrator.test/internal/approvals/by-source-key?tenantId=t1";
    server.expect(requestTo(url)).andRespond(withSuccess("""
        {"record":{"tenantId":"t1","approvalNo":"apr-1","sourceIdempotencyKey":"other"}}
        """, MediaType.APPLICATION_JSON));
    server.expect(requestTo(url)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> client.lookupBySourceKey("t1", "source-1"))
        .isInstanceOf(BizException.class);
    assertThatThrownBy(() -> client.lookupBySourceKey("t1", "source-1"))
        .isInstanceOf(BizException.class);
    server.verify();
  }

  @Test
  @DisplayName("连接失败向上保留未知状态且不会隐式重试")
  void shouldPropagateConnectionFailureWithoutRetrying() {
    server
        .expect(requestTo("http://orchestrator.test/internal/approvals/by-source-key?tenantId=t1"))
        .andRespond(request -> {
          throw new IOException("connection lost");
        });
    assertThatThrownBy(() -> client.lookupBySourceKey("t1", "source-1"))
        .isInstanceOf(ResourceAccessException.class);
    server.verify();
  }

  @Test
  @DisplayName("回查核对完整原请求，JSON 字段顺序不同仍是同一载荷")
  void shouldVerifyRecoveredApprovalAgainstFrozenSubmission() {
    server
        .expect(requestTo("http://orchestrator.test/internal/approvals/by-source-key?tenantId=t1"))
        .andRespond(withSuccess("""
            {"record":{"tenantId":"t1","approvalNo":"apr-1","sourceIdempotencyKey":"source-1",
             "approvalType":"CATCH_UP","actionType":"CATCH_UP","targetType":"CATCH_UP",
             "targetId":null,"requesterId":"operator","sourceApprovalReason":"reason",
             "approvalStatus":"APPROVED","approvalReason":"decision reason",
             "payloadJson":"{\\"jobCode\\":\\"A\\",\\"bizDate\\":\\"2026-10-29\\"}"}}
            """, MediaType.APPLICATION_JSON));
    assertThat(client.lookupVerifiedSubmission(expectedSubmission()).approvalNo())
        .isEqualTo("apr-1");
    server.verify();
  }

  @Test
  @DisplayName("同来源键但业务载荷不一致不能作为原请求回执")
  void shouldRejectRecoveredApprovalWithDifferentPayload() {
    server
        .expect(requestTo("http://orchestrator.test/internal/approvals/by-source-key?tenantId=t1"))
        .andRespond(withSuccess("""
            {"record":{"tenantId":"t1","approvalNo":"apr-1","sourceIdempotencyKey":"source-1",
             "approvalType":"CATCH_UP","actionType":"CATCH_UP","targetType":"CATCH_UP",
             "targetId":null,"requesterId":"operator","sourceApprovalReason":"reason",
             "payloadJson":"{\\"jobCode\\":\\"B\\",\\"bizDate\\":\\"2026-10-29\\"}"}}
            """, MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> client.lookupVerifiedSubmission(expectedSubmission()))
        .isInstanceOf(BizException.class);
    server.verify();
  }

  @Test
  @DisplayName("动作、目标、申请人、原因或无效载荷不匹配均不能确认回执")
  void shouldRejectRecoveredApprovalWhenFrozenFieldsDiffer() {
    String good = """
        {"record":{"tenantId":"t1","approvalNo":"apr-1","sourceIdempotencyKey":"source-1",
         "approvalType":"CATCH_UP","actionType":"CATCH_UP","targetType":"CATCH_UP",
         "targetId":null,"requesterId":"operator","sourceApprovalReason":"reason",
         "payloadJson":"{\\"jobCode\\":\\"A\\",\\"bizDate\\":\\"2026-10-29\\"}"}}
        """;
    var variants = List.of(
        good.replace("\"approvalType\":\"CATCH_UP\"", "\"approvalType\":\"OTHER\""),
        good.replace("\"actionType\":\"CATCH_UP\"", "\"actionType\":\"OTHER\""),
        good.replace("\"targetType\":\"CATCH_UP\"", "\"targetType\":\"OTHER\""),
        good.replace("\"targetId\":null", "\"targetId\":\"other\""),
        good.replace("\"requesterId\":\"operator\"", "\"requesterId\":\"other\""),
        good.replace("\"sourceApprovalReason\":\"reason\"", "\"sourceApprovalReason\":\"other\""),
        good.replace("{\\\"jobCode\\\":\\\"A\\\",\\\"bizDate\\\":\\\"2026-10-29\\\"}", "broken"));
    for (String variant : variants) {
      server
          .expect(
              requestTo("http://orchestrator.test/internal/approvals/by-source-key?tenantId=t1"))
          .andRespond(withSuccess(variant, MediaType.APPLICATION_JSON));
    }
    for (int i = 0; i < variants.size(); i++) {
      assertThatThrownBy(() -> client.lookupVerifiedSubmission(expectedSubmission()))
          .isInstanceOf(BizException.class);
    }
    server.verify();
  }

  private ApprovalSubmitCommand expectedSubmission() {
    return ApprovalSubmitCommand.builder()
        .tenantId("t1")
        .idempotencyKey("source-1")
        .approvalType("CATCH_UP")
        .actionType("CATCH_UP")
        .targetType("CATCH_UP")
        .requesterId("operator")
        .approvalReason("reason")
        .payloadJson("{\"bizDate\":\"2026-10-29\",\"jobCode\":\"A\"}")
        .build();
  }
}
