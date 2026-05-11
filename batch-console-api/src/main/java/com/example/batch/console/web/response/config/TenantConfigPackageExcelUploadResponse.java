package com.example.batch.console.web.response.config;

/** 租户配置包 Excel 上传响应：返回 token 和各 sheet 行数。 */
public record TenantConfigPackageExcelUploadResponse(
    String uploadToken,
    String fileName,
    int jobRows,
    int fileChannelRows,
    int fileTemplateRows,
    int pipelineRows,
    int pipelineStepRows,
    int workflowDefinitionRows,
    int workflowNodeRows,
    int workflowEdgeRows) {}
