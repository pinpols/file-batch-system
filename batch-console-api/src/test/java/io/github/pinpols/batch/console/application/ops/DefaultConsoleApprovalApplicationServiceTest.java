package io.github.pinpols.batch.console.application.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.file.application.ConsoleFileApplicationService;
import io.github.pinpols.batch.console.domain.job.application.ConsoleJobApprovalService;
import io.github.pinpols.batch.console.domain.job.application.ConsoleJobRecoveryService;
import io.github.pinpols.batch.console.shared.client.OrchestratorInternalRestClient;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("审批应用服务: 按动作类型委派到对应作业服务")
class DefaultConsoleApprovalApplicationServiceTest {

  private static final String TENANT_ID = "tenant-a";

  private OrchestratorInternalRestClient orchestratorClient;
  private ConsoleJobRecoveryService recoveryService;
  private ConsoleJobApprovalService approvalService;
  private DefaultConsoleApprovalApplicationService service;

  @BeforeEach
  void setUp() {
    orchestratorClient = mock(OrchestratorInternalRestClient.class);
    ConsoleRequestMetadataResolver metadataResolver = mock(ConsoleRequestMetadataResolver.class);
    recoveryService = mock(ConsoleJobRecoveryService.class);
    approvalService = mock(ConsoleJobApprovalService.class);
    service = new DefaultConsoleApprovalApplicationService(
        orchestratorClient,
        metadataResolver,
        recoveryService,
        approvalService,
        mock(ConsoleFileApplicationService.class));
    when(metadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata("req-1", "trace-1", TENANT_ID, "operator", null, null));
  }

  @Test
  @DisplayName("补偿、重跑、DLQ 重放和 catch-up 审批委派到各自的窄服务")
  void shouldDispatchApprovalActionsToDedicatedServices() {
    List<String> records = List.of(
        approval("COMPENSATION", "JOB_INSTANCE"),
        approval("RERUN", "JOB_INSTANCE"),
        approval("DLQ_REPLAY", "DLQ"),
        approval("CATCH_UP", "JOB_INSTANCE"));
    AtomicInteger index = new AtomicInteger();
    when(orchestratorClient.loadApproval(eq(TENANT_ID), any(), any())).thenAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      Class<Object> responseType = (Class<Object>) invocation.getArgument(2);
      return JsonUtils.fromJson(records.get(index.getAndIncrement()), responseType);
    });
    when(recoveryService.compensation(any(), eq("approval-1"))).thenReturn("compensated");
    when(recoveryService.rerun(any(), eq("approval-2"))).thenReturn("rerun");
    when(recoveryService.replayDeadLetter(any(), eq("approval-3"))).thenReturn("replayed");
    when(approvalService.approveCatchUp(any(), eq("approval-4"))).thenReturn("caught-up");

    assertThat(service.approve(TENANT_ID, "approval-1", "operator", "approve"))
        .isEqualTo("compensated");
    assertThat(service.approve(TENANT_ID, "approval-2", "operator", "approve")).isEqualTo("rerun");
    assertThat(service.approve(TENANT_ID, "approval-3", "operator", "approve"))
        .isEqualTo("replayed");
    assertThat(service.approve(TENANT_ID, "approval-4", "operator", "approve"))
        .isEqualTo("caught-up");

    verify(recoveryService).compensation(any(), eq("approval-1"));
    verify(recoveryService).rerun(any(), eq("approval-2"));
    verify(recoveryService).replayDeadLetter(any(), eq("approval-3"));
    verify(approvalService).approveCatchUp(any(), eq("approval-4"));
  }

  private static String approval(String actionType, String targetType) {
    return """
        {"record":{"approvalStatus":"PENDING","actionType":"%s","targetType":"%s","payloadJson":"{}"}}
        """.formatted(actionType, targetType);
  }
}
