package io.github.pinpols.batch.console.domain.job.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.application.config.ConfigBatchOperationContext;
import io.github.pinpols.batch.console.application.config.ConfigBatchOperationRequest;
import io.github.pinpols.batch.console.application.config.ConsoleConfigBatchOperationService;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigCopyService;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncBundlePayload;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.request.JobBundleImportRequest;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("作业包原请求结果恢复登记")
class DefaultConsoleJobBundleApplicationServiceTest {

  @Mock
  private TenantIdResolver tenantGuard;

  @Mock
  private ConsoleTenantConfigCopyService copyService;

  @Mock
  private ConsoleTenantConfigInitApplicationService initService;

  @Mock
  private ConsoleRequestMetadataResolver metadataResolver;

  @Mock
  private ConsoleConfigBatchOperationService operationService;

  private DefaultConsoleJobBundleApplicationService service;

  @BeforeEach
  void setUp() {
    service = new DefaultConsoleJobBundleApplicationService(
        tenantGuard,
        copyService,
        initService,
        metadataResolver,
        operationService,
        new ObjectMapper());
  }

  @Test
  @DisplayName("导入登记原请求目标顺序和汇总，再使用登记身份执行")
  void shouldRegisterOrderedTargetsBeforeImport() {
    JobBundleImportRequest request = request(List.of("target-a", "target-b", "target-a"));
    when(tenantGuard.resolveTenant("source")).thenReturn("source");
    when(metadataResolver.current()).thenReturn(metadata());
    when(operationService.begin(any())).thenAnswer(invocation -> {
      ConfigBatchOperationRequest operation = invocation.getArgument(0);
      return new ConfigBatchOperationContext(operation.auditTenantId(), operation.operationId());
    });
    when(initService.batchInit(any(), eq("admin"), any(ConfigBatchOperationContext.class)))
        .thenAnswer(invocation -> {
          ConfigBatchOperationContext context = invocation.getArgument(2);
          return new TenantConfigBatchInitResponse(
              context.operationId(), 3, 3, 0, false, List.of());
        });

    var response = service.importBundle(request, "request-hash");

    ArgumentCaptor<ConfigBatchOperationRequest> captor =
        ArgumentCaptor.forClass(ConfigBatchOperationRequest.class);
    verify(operationService).begin(captor.capture());
    ConfigBatchOperationRequest operation = captor.getValue();
    assertThat(operation.auditTenantId()).isEqualTo("source");
    assertThat(operation.requestIdentityHash()).isEqualTo("request-hash");
    assertThat(operation.operationKind()).isEqualTo("JOB_BUNDLE_IMPORT");
    assertThat(operation.targets()).containsExactly("target-a", "target-b", "target-a");
    assertThat(operation.responseMetadata().path("tenantId").asText()).isEqualTo("source");
    assertThat(
            operation.responseMetadata().path("summary").path("jobDefinitions").asInt())
        .isZero();
    assertThat(response.result().batchOperationId()).isEqualTo(operation.operationId());
  }

  @Test
  @DisplayName("目标无效时不登记操作，也不执行目标事务")
  void shouldRejectInvalidTargetsBeforeRegistration() {
    JobBundleImportRequest request = request(List.of("target-a", " "));
    when(tenantGuard.resolveTenant("source")).thenReturn("source");
    when(metadataResolver.current()).thenReturn(metadata());

    assertThatThrownBy(() -> service.importBundle(request, "request-hash"))
        .isInstanceOf(BizException.class);

    verify(operationService, never()).begin(any());
    verify(initService, never()).batchInit(any(), any(), any(ConfigBatchOperationContext.class));
  }

  @Test
  @DisplayName("预览不登记恢复操作")
  void shouldNotRegisterDryRun() {
    JobBundleImportRequest request = request(List.of("target-a"));
    request.setDryRun(true);
    when(tenantGuard.resolveTenant("source")).thenReturn("source");
    when(metadataResolver.current()).thenReturn(metadata());
    when(initService.batchInit(any(), eq("admin"), any(String.class)))
        .thenReturn(new TenantConfigBatchInitResponse("operation", 1, 1, 0, true, List.of()));

    var response = service.importBundle(request, "request-hash");

    assertThat(response.result().dryRun()).isTrue();
    verify(operationService, never()).begin(any());
  }

  private JobBundleImportRequest request(List<String> targets) {
    JobBundleImportRequest request = new JobBundleImportRequest();
    request.setTenantId("source");
    request.setTargetTenantIds(targets);
    request.setBundle(new ConfigSyncBundlePayload());
    return request;
  }

  private ConsoleRequestMetadata metadata() {
    return new ConsoleRequestMetadata(null, null, "source", "admin", null, null);
  }
}
