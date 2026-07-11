package io.github.pinpols.batch.console.domain.job.infrastructure;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigCopyService;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import io.github.pinpols.batch.console.domain.job.application.ConsoleJobBundleApplicationService;
import io.github.pinpols.batch.console.domain.job.web.request.JobBundleCreateRequest;
import io.github.pinpols.batch.console.domain.job.web.request.JobBundleImportRequest;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleJobBundleExportResponse;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleJobBundleResultResponse;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleJobBundleSummaryResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import io.github.pinpols.batch.console.web.request.config.ConfigSyncBundlePayload;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.web.response.config.TenantConfigBatchInitResponse;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DefaultConsoleJobBundleApplicationService
    implements ConsoleJobBundleApplicationService {

  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleTenantConfigCopyService tenantConfigCopyService;
  private final ConsoleTenantConfigInitApplicationService initApplicationService;
  private final ConsoleRequestMetadataResolver metadataResolver;

  @Override
  public ConsoleJobBundleExportResponse exportBundle(String tenantId, String jobCode) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    ConfigSyncBundlePayload bundle =
        tenantConfigCopyService.buildJobBundle(resolvedTenantId, jobCode);
    if (bundle.getJobDefinitions() == null || bundle.getJobDefinitions().isEmpty()) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.job.definition_not_found", jobCode);
    }
    return new ConsoleJobBundleExportResponse(
        resolvedTenantId, jobCode, ConsoleJobBundleSummaryResponse.from(bundle), bundle);
  }

  @Override
  public ConsoleJobBundleResultResponse create(JobBundleCreateRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    TenantConfigBatchInitRequest initRequest = toInitRequest(request.getBundle());
    initRequest.setTargetTenantIds(List.of(tenantId));
    initRequest.setMode(request.getMode());
    initRequest.setDryRun(request.isDryRun());
    // Job Bundle 严格 all-or-nothing：任一 spec 失败即整体回滚（文档 console-api.openapi.yaml#3557 已声明）
    initRequest.setStrict(true);
    TenantConfigBatchInitResponse response =
        initApplicationService.batchInit(initRequest, operator(), UUID.randomUUID().toString());
    return new ConsoleJobBundleResultResponse(
        tenantId, ConsoleJobBundleSummaryResponse.from(request.getBundle()), response);
  }

  @Override
  public ConsoleJobBundleResultResponse importBundle(JobBundleImportRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    TenantConfigBatchInitRequest initRequest = toInitRequest(request.getBundle());
    initRequest.setTargetTenantIds(request.getTargetTenantIds());
    initRequest.setMode(request.getMode());
    initRequest.setDryRun(request.isDryRun());
    // Job Bundle 严格 all-or-nothing：任一 spec 失败即整体回滚（文档 console-api.openapi.yaml#3557 已声明）
    initRequest.setStrict(true);
    TenantConfigBatchInitResponse response =
        initApplicationService.batchInit(initRequest, operator(), UUID.randomUUID().toString());
    return new ConsoleJobBundleResultResponse(
        tenantId, ConsoleJobBundleSummaryResponse.from(request.getBundle()), response);
  }

  private TenantConfigBatchInitRequest toInitRequest(ConfigSyncBundlePayload bundle) {
    if (bundle == null) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.bundle_required");
    }
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setJobDefinitions(bundle.getJobDefinitions());
    request.setWorkflowDefinitions(bundle.getWorkflowDefinitions());
    request.setPipelineDefinitions(bundle.getPipelineDefinitions());
    request.setFileChannels(bundle.getFileChannels());
    request.setFileTemplates(bundle.getFileTemplates());
    request.setResourceQueues(bundle.getResourceQueues());
    request.setBatchWindows(bundle.getBatchWindows());
    request.setBusinessCalendars(bundle.getBusinessCalendars());
    request.setQuotaPolicies(bundle.getQuotaPolicies());
    request.setAlertRoutings(bundle.getAlertRoutings());
    return request;
  }

  private String operator() {
    String operator = metadataResolver.current().operatorId();
    return operator == null || operator.isBlank() ? "system" : operator;
  }
}
