package io.github.pinpols.batch.console.domain.workflow.web.response;

/**
 * Workflow DAG designer 下拉数据源通用条目(BE Spike,docs/design/workflow-dag-designer.md)。
 *
 * <p>用于 job-definitions/codes / pipeline-definitions/codes 等下拉,仅返回 (code, name) 二元组,不含完整定义体, 减少前端
 * payload。
 */
public record CodeNameOption(String code, String name) {}
