package com.example.batch.console.web.response;

/** 租户配置包 Excel 上传响应：返回 token 和各 sheet 行数。 */
public record TenantConfigPackageExcelUploadResponse(
    String uploadToken,
    String fileName,
    int jobRows,
    int fileChannelRows,
    int alertRoutingRows,
    int pipelineRows,
    int pipelineStepRows,
    int workflowDefinitionRows,
    int workflowNodeRows,
    int workflowEdgeRows) {}
