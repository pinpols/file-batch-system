package com.example.batch.console.domain.workflow.web.response;

import java.time.Instant;

/**
 * workflow-dag-designer Polish — 版本列表项。
 *
 * <p>{@code current=true} 表示该 version 与主表 {@code workflow_definition.version} 一致;其余为历史快照。
 * 历史表无数据(刚迁移后)时降级为单条 current,与原 PR #370 行为一致。
 */
public record WorkflowDefinitionVersionSummaryResponse(
    Integer version, String savedBy, Instant savedAt, String summary, Boolean current) {}
