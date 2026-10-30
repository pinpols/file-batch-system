package io.github.pinpols.batch.worker.core.support;

import java.util.Map;

/** 单条文件 Pipeline 的 CLAIM 载荷及预期模板身份。 */
public record FileTemplateConfigRequest(
    String tenantId,
    String templateCode,
    String templateType,
    String rawPayload,
    Map<String, Object> attributes) {}
