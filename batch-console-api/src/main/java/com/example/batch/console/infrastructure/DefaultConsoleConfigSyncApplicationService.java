package com.example.batch.console.infrastructure;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.application.ConsoleConfigSyncApplicationService;
import com.example.batch.console.application.ConsoleTenantConfigInitApplicationService;
import com.example.batch.console.mapper.ConfigSyncLogMapper;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.ConsoleTenantConfigCopyService;
import com.example.batch.console.web.request.ConfigSyncBundlePayload;
import com.example.batch.console.web.request.ConfigSyncExportRequest;
import com.example.batch.console.web.request.ConfigSyncImportRequest;
import com.example.batch.console.web.request.ConfigSyncPreviewRequest;
import com.example.batch.console.web.request.TenantConfigBatchInitRequest;
import com.example.batch.console.web.request.TenantConfigCopyRequest;
import com.example.batch.console.web.response.TenantConfigBatchInitResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;


@Service
@RequiredArgsConstructor
public class DefaultConsoleConfigSyncApplicationService implements ConsoleConfigSyncApplicationService {

    private final ConsoleTenantGuard tenantGuard;
    private final ConsoleTenantConfigCopyService tenantConfigCopyService;
    private final ConsoleTenantConfigInitApplicationService initApplicationService;
    private final ConsoleRequestMetadataResolver metadataResolver;
    private final ConfigSyncLogMapper configSyncLogMapper;
    private final PlatformTransactionManager transactionManager;

    @Override
    public Map<String, Object> export(ConfigSyncExportRequest request) {
        String tenantId = tenantGuard.resolveTenant(request.getSourceTenantId());
        ConfigSyncBundlePayload bundle = tenantConfigCopyService.buildBundle(tenantId, request.getConfigTypes());
        return mapOf(
                "sourceTenantId", tenantId,
                "sourceEnv", request.getSourceEnv(),
                "targetEnv", request.getTargetEnv(),
                "summary", summarize(bundle),
                "bundle", bundle
        );
    }

    @Override
    public Map<String, Object> preview(ConfigSyncPreviewRequest request) {
        String sourceTenantId = tenantGuard.resolveTenant(request.getSourceTenantId());
        String tenantId = tenantGuard.resolveTenant(request.getTenantId());
        ConfigSyncBundlePayload bundle = tenantConfigCopyService.buildBundle(sourceTenantId, request.getConfigTypes());
        return mapOf(
                "tenantId", tenantId,
                "sourceTenantId", sourceTenantId,
                "sourceEnv", request.getSourceEnv(),
                "targetEnv", request.getTargetEnv(),
                "summary", summarize(bundle)
        );
    }

    @Override
    @Transactional
    public Map<String, Object> importBundle(ConfigSyncImportRequest request) {
        String tenantId = tenantGuard.resolveTenant(request.getTenantId());
        if (request.getBundle() == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "bundle must not be null");
        }
        Long logId = createLog(tenantId, request, request.getBundle());
        try {
            TenantConfigBatchInitRequest initRequest = toInitRequest(request);
            String operator = metadataResolver.current().operatorId();
            if (operator == null || operator.isBlank()) {
                operator = "system";
            }
            TenantConfigBatchInitResponse response = initApplicationService.batchInit(initRequest, operator, UUID.randomUUID().toString());
            updateLog(logId, tenantId, response);
            return mapOf(
                    "syncLogId", logId,
                    "summary", summarize(request.getBundle()),
                    "result", response
            );
        } catch (RuntimeException ex) {
            markLogFailed(tenantId, logId, totalCount(request.getBundle()), ex.getMessage());
            throw ex;
        }
    }

    @Override
    public List<Map<String, Object>> logs(String tenantId, int limit) {
        return configSyncLogMapper.selectByTenant(tenantGuard.resolveTenant(tenantId), Math.min(Math.max(limit, 1), 200));
    }

    private void markLogFailed(String tenantId, Long logId, int failedItems, String errorMessage) {
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.executeWithoutResult(status -> configSyncLogMapper.updateResult(mapOf(
                "tenantId", tenantId,
                "id", logId,
                "syncStatus", "FAILED",
                "successItems", 0,
                "failedItems", failedItems,
                "skippedItems", 0,
                "detailJson", JsonUtils.toJson(mapOf("error", errorMessage))
        )));
    }

    private Long createLog(String tenantId, ConfigSyncImportRequest request, ConfigSyncBundlePayload bundle) {
        Map<String, Integer> summary = summarize(bundle);
        int totalItems = summary.values().stream().mapToInt(Integer::intValue).sum();
        String operator = metadataResolver.current().operatorId();
        Map<String, Object> params = mapOf(
                "tenantId", tenantId,
                "syncDirection", "IMPORT",
                "sourceEnv", request.getSourceEnv(),
                "targetEnv", request.getTargetEnv(),
                "configTypes", String.join(",", summary.keySet()),
                "totalItems", totalItems,
                "successItems", 0,
                "failedItems", 0,
                "skippedItems", 0,
                "syncStatus", "RUNNING",
                "detailJson", JsonUtils.toJson(mapOf("summary", summary, "dryRun", request.isDryRun())),
                "operatorId", operator
        );
        configSyncLogMapper.insert(params);
        return longValue(params.get("id"));
    }

    private void updateLog(Long logId, String tenantId, TenantConfigBatchInitResponse response) {
        int total = response.totalTenants();
        int success = response.successTenants();
        int failed = response.failureTenants();
        configSyncLogMapper.updateResult(mapOf(
                "tenantId", tenantId,
                "id", logId,
                "syncStatus", failed > 0 ? "PARTIAL_FAILED" : "SUCCESS",
                "successItems", success,
                "failedItems", failed,
                "skippedItems", Math.max(total - success - failed, 0),
                "detailJson", JsonUtils.toJson(response)
        ));
    }

    private TenantConfigBatchInitRequest toInitRequest(ConfigSyncImportRequest request) {
        TenantConfigBatchInitRequest initRequest = new TenantConfigBatchInitRequest();
        initRequest.setTargetTenantIds(request.getTargetTenantIds());
        initRequest.setMode(request.getMode());
        initRequest.setDryRun(request.isDryRun());
        ConfigSyncBundlePayload bundle = request.getBundle();
        initRequest.setJobDefinitions(bundle.getJobDefinitions());
        initRequest.setWorkflowDefinitions(bundle.getWorkflowDefinitions());
        initRequest.setPipelineDefinitions(bundle.getPipelineDefinitions());
        initRequest.setFileChannels(bundle.getFileChannels());
        initRequest.setFileTemplates(bundle.getFileTemplates());
        return initRequest;
    }

    private Map<String, Integer> summarize(ConfigSyncBundlePayload bundle) {
        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("jobDefinitions", sizeOf(bundle.getJobDefinitions()));
        summary.put("workflowDefinitions", sizeOf(bundle.getWorkflowDefinitions()));
        summary.put("pipelineDefinitions", sizeOf(bundle.getPipelineDefinitions()));
        summary.put("fileChannels", sizeOf(bundle.getFileChannels()));
        summary.put("fileTemplates", sizeOf(bundle.getFileTemplates()));
        return summary;
    }

    private int totalCount(ConfigSyncBundlePayload bundle) {
        return summarize(bundle).values().stream().mapToInt(Integer::intValue).sum();
    }

    private int sizeOf(List<?> list) {
        return list == null ? 0 : list.size();
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(String.valueOf(value));
    }

    private Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return result;
    }
}
