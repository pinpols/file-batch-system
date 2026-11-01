package io.github.pinpols.batch.console.domain.job.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.application.config.ConfigBatchOperationContext;
import io.github.pinpols.batch.console.application.config.ConfigBatchOperationRequest;
import io.github.pinpols.batch.console.application.config.ConsoleConfigBatchOperationService;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigCopyService;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncBundlePayload;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse;
import io.github.pinpols.batch.console.domain.job.application.ConsoleJobBundleApplicationService;
import io.github.pinpols.batch.console.domain.job.application.contract.request.JobBundleCreateRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.request.JobBundleImportRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobBundleExportResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobBundleResultResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobBundleSummaryResponse;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DefaultConsoleJobBundleApplicationService
    implements ConsoleJobBundleApplicationService {

  private final TenantIdResolver tenantGuard;
  private final ConsoleTenantConfigCopyService tenantConfigCopyService;
  private final ConsoleTenantConfigInitApplicationService initApplicationService;
  private final ConsoleRequestMetadataResolver metadataResolver;
  private final ConsoleConfigBatchOperationService operationService;
  private final ObjectMapper objectMapper;

  @Override
  public ConsoleJobBundleExportResponse exportBundle(String tenantId, String jobCode) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    ConfigSyncBundlePayload bundle =
        tenantConfigCopyService.buildJobBundle(resolvedTenantId, jobCode);
    if (bundle.getJobDefinitions() == null || bundle.getJobDefinitions().isEmpty()) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.job.definition_not_found", jobCode);
    }
    ConsoleJobBundleSummaryResponse summary = ConsoleJobBundleSummaryResponse.from(bundle);
    return new ConsoleJobBundleExportResponse(resolvedTenantId, jobCode, summary, bundle);
  }

  @Override
  public ConsoleJobBundleResultResponse create(JobBundleCreateRequest request) {
    return create(request, null);
  }

  @Override
  public ConsoleJobBundleResultResponse create(JobBundleCreateRequest request, String requestHash) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    TenantConfigBatchInitRequest initRequest = toInitRequest(request.getBundle());
    initRequest.setTargetTenantIds(List.of(tenantId));
    initRequest.setMode(request.getMode());
    initRequest.setDryRun(request.isDryRun());
    // Job Bundle 严格 all-or-nothing：任一 spec 失败即整体回滚（文档 console-api.openapi.yaml#3557 已声明）
    initRequest.setStrict(true);
    String operator = operator();
    UUID operationId = UUID.randomUUID();
    String operationIdText = operationId.toString();
    ConsoleJobBundleSummaryResponse summary =
        ConsoleJobBundleSummaryResponse.from(request.getBundle());
    TenantConfigBatchInitResponse response = executeBatch(
        initRequest, operator, operationIdText, requestHash, "JOB_BUNDLE_CREATE", tenantId, summary);
    return new ConsoleJobBundleResultResponse(tenantId, summary, response);
  }

  @Override
  public ConsoleJobBundleResultResponse importBundle(JobBundleImportRequest request) {
    return importBundle(request, null);
  }

  @Override
  public ConsoleJobBundleResultResponse importBundle(
      JobBundleImportRequest request, String requestHash) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    TenantConfigBatchInitRequest initRequest = toInitRequest(request.getBundle());
    initRequest.setTargetTenantIds(request.getTargetTenantIds());
    initRequest.setMode(request.getMode());
    initRequest.setDryRun(request.isDryRun());
    // Job Bundle 严格 all-or-nothing：任一 spec 失败即整体回滚（文档 console-api.openapi.yaml#3557 已声明）
    initRequest.setStrict(true);
    String operator = operator();
    UUID operationId = UUID.randomUUID();
    String operationIdText = operationId.toString();
    ConsoleJobBundleSummaryResponse summary =
        ConsoleJobBundleSummaryResponse.from(request.getBundle());
    TenantConfigBatchInitResponse response = executeBatch(
        initRequest, operator, operationIdText, requestHash, "JOB_BUNDLE_IMPORT", tenantId, summary);
    return new ConsoleJobBundleResultResponse(tenantId, summary, response);
  }

  private TenantConfigBatchInitResponse executeBatch(
      TenantConfigBatchInitRequest request,
      String operator,
      String operationId,
      String requestHash,
      String operationKind,
      String tenantId,
      ConsoleJobBundleSummaryResponse summary) {
    if (requestHash == null || request.isDryRun()) {
      return initApplicationService.batchInit(request, operator, operationId);
    }
    List<String> targets = request.getTargetTenantIds();
    if (EmptyChecks.isEmpty(targets) || targets.stream().anyMatch(EmptyChecks::isBlank)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument");
    }
    ObjectNode responseMetadata = objectMapper.createObjectNode();
    responseMetadata.put("tenantId", tenantId);
    responseMetadata.set("summary", objectMapper.valueToTree(summary));
    ConfigBatchOperationRequest operation = new ConfigBatchOperationRequest(
        tenantId, operationId, requestHash, operationKind, targets, responseMetadata);
    ConfigBatchOperationContext context = operationService.begin(operation);
    return initApplicationService.batchInit(request, operator, context);
  }

  private TenantConfigBatchInitRequest toInitRequest(ConfigSyncBundlePayload bundle) {
    if (bundle == null) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.bundle_required");
    }
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setJobDefinitions(bundle.getJobDefinitions());
    request.setWorkflowDefinitions(bundle.getWorkflowDefinitions());
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
