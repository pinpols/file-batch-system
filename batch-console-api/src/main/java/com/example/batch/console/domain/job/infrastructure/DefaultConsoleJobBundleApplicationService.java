package com.example.batch.console.domain.job.infrastructure;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.application.config.ConsoleTenantConfigCopyService;
import com.example.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import com.example.batch.console.application.job.ConsoleJobBundleApplicationService;
import com.example.batch.console.domain.job.application.ConsoleJobBundleApplicationService;
import com.example.batch.console.domain.job.web.request.JobBundleCreateRequest;
import com.example.batch.console.domain.job.web.request.JobBundleImportRequest;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.request.config.ConfigSyncBundlePayload;
import com.example.batch.console.web.request.config.TenantConfigBatchInitRequest;
import com.example.batch.console.web.response.config.TenantConfigBatchInitResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DefaultConsoleJobBundleApplicationService
    implements ConsoleJobBundleApplicationService {

  private static final String KEY_BUNDLE = "bundle";
  private static final String KEY_SUMMARY = "summary";
  private static final String KEY_TENANT_ID = "tenantId";

  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleTenantConfigCopyService tenantConfigCopyService;
  private final ConsoleTenantConfigInitApplicationService initApplicationService;
  private final ConsoleRequestMetadataResolver metadataResolver;

  @Override
  public Map<String, Object> exportBundle(String tenantId, String jobCode) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    ConfigSyncBundlePayload bundle =
        tenantConfigCopyService.buildJobBundle(resolvedTenantId, jobCode);
    if (sizeOf(bundle.getJobDefinitions()) == 0) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.job.definition_not_found", jobCode);
    }
    return mapOf(
        KEY_TENANT_ID,
        resolvedTenantId,
        "jobCode",
        jobCode,
        KEY_SUMMARY,
        summarize(bundle),
        KEY_BUNDLE,
        bundle);
  }

  @Override
  public Map<String, Object> create(JobBundleCreateRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    TenantConfigBatchInitRequest initRequest = toInitRequest(request.getBundle());
    initRequest.setTargetTenantIds(List.of(tenantId));
    initRequest.setMode(request.getMode());
    initRequest.setDryRun(request.isDryRun());
    // Job Bundle 严格 all-or-nothing：任一 spec 失败即整体回滚（文档 console-api.openapi.yaml#3557 已声明）
    initRequest.setStrict(true);
    TenantConfigBatchInitResponse response =
        initApplicationService.batchInit(initRequest, operator(), UUID.randomUUID().toString());
    return mapOf(
        KEY_TENANT_ID, tenantId, KEY_SUMMARY, summarize(request.getBundle()), "result", response);
  }

  @Override
  public Map<String, Object> importBundle(JobBundleImportRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    TenantConfigBatchInitRequest initRequest = toInitRequest(request.getBundle());
    initRequest.setTargetTenantIds(request.getTargetTenantIds());
    initRequest.setMode(request.getMode());
    initRequest.setDryRun(request.isDryRun());
    // Job Bundle 严格 all-or-nothing：任一 spec 失败即整体回滚（文档 console-api.openapi.yaml#3557 已声明）
    initRequest.setStrict(true);
    TenantConfigBatchInitResponse response =
        initApplicationService.batchInit(initRequest, operator(), UUID.randomUUID().toString());
    return mapOf(
        KEY_TENANT_ID, tenantId, KEY_SUMMARY, summarize(request.getBundle()), "result", response);
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

  private Map<String, Integer> summarize(ConfigSyncBundlePayload bundle) {
    Map<String, Integer> summary = new LinkedHashMap<>();
    summary.put("jobDefinitions", sizeOf(bundle.getJobDefinitions()));
    summary.put("workflowDefinitions", sizeOf(bundle.getWorkflowDefinitions()));
    summary.put("pipelineDefinitions", sizeOf(bundle.getPipelineDefinitions()));
    summary.put("fileChannels", sizeOf(bundle.getFileChannels()));
    summary.put("fileTemplates", sizeOf(bundle.getFileTemplates()));
    summary.put("resourceQueues", sizeOf(bundle.getResourceQueues()));
    summary.put("batchWindows", sizeOf(bundle.getBatchWindows()));
    summary.put("businessCalendars", sizeOf(bundle.getBusinessCalendars()));
    summary.put("quotaPolicies", sizeOf(bundle.getQuotaPolicies()));
    summary.put("alertRoutings", sizeOf(bundle.getAlertRoutings()));
    return summary;
  }

  private int sizeOf(List<?> list) {
    return list == null ? 0 : list.size();
  }

  private Map<String, Object> mapOf(Object... pairs) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      result.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    return result;
  }
}
