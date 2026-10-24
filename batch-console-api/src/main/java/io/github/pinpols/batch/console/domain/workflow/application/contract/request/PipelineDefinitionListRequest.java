package io.github.pinpols.batch.console.domain.workflow.application.contract.request;

/** 列表请求保留原始筛选与页码，租户授权和分页边界仍由应用服务解析。 */
public record PipelineDefinitionListRequest(
    String tenantId, String jobCode, String pipelineType, Boolean enabled, int pageNo, int pageSize) {}
