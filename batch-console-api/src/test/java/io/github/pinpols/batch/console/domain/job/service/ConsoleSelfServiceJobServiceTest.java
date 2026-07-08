package io.github.pinpols.batch.console.domain.job.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.job.service.ConsoleSelfServiceJobService.CompensationParam;
import io.github.pinpols.batch.console.domain.job.service.ConsoleSelfServiceJobService.RerunParam;
import io.github.pinpols.batch.console.domain.ops.infrastructure.OrchestratorApprovalClient;
import io.github.pinpols.batch.console.domain.ops.infrastructure.OrchestratorApprovalClient.ApprovalSubmitResponse;
import io.github.pinpols.batch.console.domain.ops.infrastructure.OrchestratorInternalRestClient;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class ConsoleSelfServiceJobServiceTest {

  private OrchestratorInternalRestClient orchestratorInternalRestClient;
  private ConsoleRequestMetadataResolver metadataResolver;
  private ConsoleTenantGuard tenantGuard;
  private ConsoleSelfServiceJobService service;

  private RestClient restClient;
  private RestClient.RequestBodyUriSpec bodyUriSpec;
  private RestClient.RequestBodySpec bodySpec;
  private RestClient.ResponseSpec responseSpec;

  @BeforeEach
  void setUp() {
    orchestratorInternalRestClient = mock(OrchestratorInternalRestClient.class);
    metadataResolver = mock(ConsoleRequestMetadataResolver.class);
    tenantGuard = mock(ConsoleTenantGuard.class);

    restClient = mock(RestClient.class);
    bodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
    bodySpec = mock(RestClient.RequestBodySpec.class);
    responseSpec = mock(RestClient.ResponseSpec.class);

    when(orchestratorInternalRestClient.build()).thenReturn(restClient);
    when(restClient.post()).thenReturn(bodyUriSpec);
    when(bodyUriSpec.uri(anyString())).thenReturn(bodySpec);
    // varargs: header(String, String...) — use doReturn with explicit String[] cast
    doReturn(bodySpec).when(bodySpec).header(anyString(), (String[]) any());
    when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
    when(bodySpec.retrieve()).thenReturn(responseSpec);
    when(metadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata("req-1", "trace-1", "tenant-a", "operator-1", null, null));

    // 用真实共享审批客户端 + mock 的 REST 层，保持对提交链路（header/body/错误 key）的行为覆盖。
    OrchestratorApprovalClient approvalClient =
        new OrchestratorApprovalClient(orchestratorInternalRestClient, metadataResolver);
    service = new ConsoleSelfServiceJobService(approvalClient, tenantGuard);
  }

  @Test
  void shouldSubmitRerunApproval() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(responseSpec.body(ApprovalSubmitResponse.class))
        .thenReturn(new ApprovalSubmitResponse("APR-001"));

    RerunParam param = new RerunParam("tenant-a", "JOB-01", "2026-04-10", "INST-001", "test rerun");
    String approvalNo = service.requestRerun(param, "operator-1", "idem-key-1");

    assertThat(approvalNo).isEqualTo("APR-001");
  }

  @Test
  void shouldSubmitCompensationApproval() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(responseSpec.body(ApprovalSubmitResponse.class))
        .thenReturn(new ApprovalSubmitResponse("APR-002"));

    CompensationParam param =
        new CompensationParam(
            "tenant-a", "JOB-02", "2026-04-10", "FULL", "INST-002", "test compensation");
    String approvalNo = service.requestCompensation(param, "operator-2", "idem-key-2");

    assertThat(approvalNo).isEqualTo("APR-002");
  }

  @Test
  void shouldThrowWhenApprovalResponseMissing() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(responseSpec.body(ApprovalSubmitResponse.class)).thenReturn(null);

    RerunParam param = new RerunParam("tenant-a", "JOB-01", "2026-04-10", "INST-001", "test rerun");

    assertThatThrownBy(() -> service.requestRerun(param, "operator-1", "idem-key-1"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("submit_failed");
  }
}
