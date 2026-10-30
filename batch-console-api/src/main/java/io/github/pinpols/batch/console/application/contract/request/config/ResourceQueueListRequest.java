package io.github.pinpols.batch.console.application.contract.request.config;

/** 列表请求保留原始筛选与页码，租户授权和分页边界仍由应用服务解析。 */
public record ResourceQueueListRequest(
    String tenantId,
    String queueCode,
    String queueType,
    Boolean enabled,
    int pageNo,
    int pageSize) {}
