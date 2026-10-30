package io.github.pinpols.batch.console.domain.job.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.job.entity.JobInstanceEntity;
import io.github.pinpols.batch.console.domain.job.mapper.JobInstanceMapper;
import io.github.pinpols.batch.console.domain.job.service.ConsoleSelfServiceJobService.CompensationParam;
import io.github.pinpols.batch.console.domain.job.service.ConsoleSelfServiceJobService.RerunParam;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient.ApprovalSubmitResponse;
import io.github.pinpols.batch.console.shared.client.OrchestratorInternalRestClient;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClient;

@DisplayName("自助作业服务的审批提交链路: 重跑与补偿请求经共享客户端下发并处理响应缺失")
class ConsoleSelfServiceJobServiceTest {

  private OrchestratorInternalRestClient orchestratorInternalRestClient;
  private ConsoleRequestMetadataResolver metadataResolver;
  private ConsoleTenantGuard tenantGuard;
  private JobInstanceMapper jobInstanceMapper;
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
    jobInstanceMapper = mock(JobInstanceMapper.class);

    restClient = mock(RestClient.class);
    bodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
    bodySpec = mock(RestClient.RequestBodySpec.class);
    responseSpec = mock(RestClient.ResponseSpec.class);

    when(orchestratorInternalRestClient.client()).thenReturn(restClient);
    when(restClient.post()).thenReturn(bodyUriSpec);
    when(bodyUriSpec.uri(anyString())).thenReturn(bodySpec);
    // 可变参数：header(String, String...)；调用 doReturn 时应显式转换为 String[]。
    doReturn(bodySpec).when(bodySpec).header(anyString(), (String[]) any());
    when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
    when(bodySpec.retrieve()).thenReturn(responseSpec);
    when(metadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata("req-1", "trace-1", "tenant-a", "operator-1", null, null));

    // 用真实共享审批客户端 + mock 的 REST 层，保持对提交链路（header/body/错误 key）的行为覆盖。
    OrchestratorApprovalClient approvalClient =
        new OrchestratorApprovalClient(orchestratorInternalRestClient, metadataResolver);
    service = new ConsoleSelfServiceJobService(approvalClient, tenantGuard, jobInstanceMapper);
  }

  @Test
  @DisplayName("重跑申请提交成功后返回审批单号")
  void shouldSubmitRerunApproval() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(responseSpec.body(ApprovalSubmitResponse.class))
        .thenReturn(new ApprovalSubmitResponse("APR-001"));

    RerunParam param = new RerunParam("tenant-a", "JOB-01", "2026-04-10", "INST-001", "test rerun");
    String approvalNo = service.requestRerun(param, "operator-1", "idem-key-1");

    assertThat(approvalNo).isEqualTo("APR-001");
  }

  @Test
  @DisplayName("补偿申请提交成功后返回审批单号")
  void shouldSubmitCompensationApproval() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    JobInstanceEntity target = new JobInstanceEntity();
    target.setId(102L);
    target.setJobCode("JOB-02");
    target.setBizDate(LocalDate.parse("2026-04-10"));
    target.setInstanceNo("INST-002");
    when(jobInstanceMapper.selectByInstanceNos("tenant-a", List.of("INST-002")))
        .thenReturn(List.of(target));
    when(responseSpec.body(ApprovalSubmitResponse.class))
        .thenReturn(new ApprovalSubmitResponse("APR-002"));

    CompensationParam param = new CompensationParam(
        "tenant-a", "JOB-02", "2026-04-10", "JOB", "INST-002", "test compensation");
    String approvalNo = service.requestCompensation(param, "operator-2", "idem-key-2");

    assertThat(approvalNo).isEqualTo("APR-002");
    verify(jobInstanceMapper).selectByInstanceNos("tenant-a", List.of("INST-002"));
  }

  @Test
  @DisplayName("拒绝未支持的补偿类型或与所选作业业务日不匹配的目标实例")
  void shouldRejectUnsupportedOrMismatchedCompensationTarget() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    CompensationParam unsupported =
        new CompensationParam("tenant-a", "JOB-02", "2026-04-10", "STEP", "INST-002", "test");
    assertThatThrownBy(() -> service.requestCompensation(unsupported, "operator-2", "idem-key-3"))
        .isInstanceOf(BizException.class);

    JobInstanceEntity mismatch = new JobInstanceEntity();
    mismatch.setId(103L);
    mismatch.setJobCode("JOB-OTHER");
    mismatch.setBizDate(LocalDate.parse("2026-04-10"));
    mismatch.setInstanceNo("INST-002");
    when(jobInstanceMapper.selectByInstanceNos("tenant-a", List.of("INST-002")))
        .thenReturn(List.of(mismatch));
    CompensationParam mismatched =
        new CompensationParam("tenant-a", "JOB-02", "2026-04-10", "JOB", "INST-002", "test");
    assertThatThrownBy(() -> service.requestCompensation(mismatched, "operator-2", "idem-key-4"))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("审批目标使用经租户、作业和业务日核验的实例 ID")
  void shouldBindApprovalToResolvedJobInstanceId() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    JobInstanceEntity target = new JobInstanceEntity();
    target.setId(104L);
    target.setJobCode("JOB-02");
    target.setBizDate(LocalDate.parse("2026-04-10"));
    target.setInstanceNo("INST-004");
    when(jobInstanceMapper.selectByInstanceNos("tenant-a", List.of("INST-004")))
        .thenReturn(List.of(target));
    OrchestratorApprovalClient approvalClient = mock(OrchestratorApprovalClient.class);
    when(approvalClient.submitApproval(any())).thenReturn("APR-004");
    service = new ConsoleSelfServiceJobService(approvalClient, tenantGuard, jobInstanceMapper);

    service.requestCompensation(
        new CompensationParam("tenant-a", "JOB-02", "2026-04-10", "JOB", "INST-004", "reason"),
        "operator-1",
        "idem-key-4");

    ArgumentCaptor<OrchestratorApprovalClient.ApprovalSubmitCommand> commandCaptor =
        ArgumentCaptor.forClass(OrchestratorApprovalClient.ApprovalSubmitCommand.class);
    verify(approvalClient).submitApproval(commandCaptor.capture());
    assertThat(commandCaptor.getValue().targetId()).isEqualTo("104");
    assertThat(commandCaptor.getValue().payloadJson()).contains("\"targetId\":104");
  }

  @Test
  @DisplayName("审批响应体缺失时抛出业务异常并提示提交失败")
  void shouldThrowWhenApprovalResponseMissing() {
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(responseSpec.body(ApprovalSubmitResponse.class)).thenReturn(null);

    RerunParam param = new RerunParam("tenant-a", "JOB-01", "2026-04-10", "INST-001", "test rerun");

    assertThatThrownBy(() -> service.requestRerun(param, "operator-1", "idem-key-1"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("submit_failed");
  }
}
