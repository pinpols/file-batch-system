package com.example.batch.console.domain.workflow.web.response;

import java.time.Instant;

/**
 * 工作流定义版本摘要(用于版本下拉列表 + diff 页)。
 *
 * <p>workflow-dag-designer Polish(V167 历史表闭环):
 *
 * <ul>
 *   <li>{@code current=true} 表示该 version 与主表 {@code workflow_definition.version} 一致;其余为历史快照。
 *   <li>历史表无数据时(刚迁移后)降级为单条 current,与 PR #370 行为一致。
 * </ul>
 *
 * @param version 版本号(对应 workflow_definition.version)
 * @param savedBy 写入人 username(SecurityContext.username);降级期为 null
 * @param savedAt 写入时间;降级期取 workflow_definition.updated_at
 * @param summary 版本备注;Spike 不展示,FE 未提交字段
 * @param current 是否当前生效版本
 */
public record WorkflowDefinitionVersionSummaryResponse(
    Integer version, String savedBy, Instant savedAt, String summary, Boolean current) {}
