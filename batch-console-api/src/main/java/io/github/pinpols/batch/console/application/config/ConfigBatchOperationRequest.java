package io.github.pinpols.batch.console.application.config;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** 可恢复批操作登记：只包含目标身份和非敏感响应元数据。 */
public record ConfigBatchOperationRequest(
        String auditTenantId,
        String operationId,
        String requestIdentityHash,
        String operationKind,
        List<String> targets,
        JsonNode responseMetadata) {}
