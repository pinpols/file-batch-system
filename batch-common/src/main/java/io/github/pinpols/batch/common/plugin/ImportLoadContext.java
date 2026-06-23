package io.github.pinpols.batch.common.plugin;

import java.util.Map;

/** 传给 {@link ImportLoadPlugin} 的 LOAD 阶段上下文。由平台填充;插件不得假设固定的 entity 类型。 */
public record ImportLoadContext(
    String tenantId,
    String jobCode,
    String traceId,
    String workerId,
    String sourceFileName,
    String batchNo,
    String bizDate,
    String bizType,
    String region,
    String templateCode,
    Map<String, Object> templateConfig) {}
