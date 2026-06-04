package com.example.batch.console.domain.workflow.web.response;

import java.time.Instant;

/**
 * 工作流定义版本摘要(用于版本下拉列表)。
 *
 * <p>当前实现降级:平台尚无 {@code workflow_definition_version} 历史表(见 docs/api/console-api-protocol.md
 * Changelog 2026-06-04),listVersions 只返回当前一条记录,等同 workflow_definition 主表当前快照。完整历史需后续 migration
 * 引入归档表,届时 {@code savedBy} 才会承载真实写入人。
 *
 * @param version 版本号(对应 workflow_definition.version)
 * @param savedBy 写入人 username,降级期为 null
 * @param savedAt 写入时间,降级期取 workflow_definition.updated_at
 * @param summary 版本备注,降级期为 null(无历史表无法记录)
 * @param current 是否当前生效版本,降级期恒为 true
 */
public record WorkflowDefinitionVersionSummaryResponse(
    Integer version, String savedBy, Instant savedAt, String summary, Boolean current) {}
