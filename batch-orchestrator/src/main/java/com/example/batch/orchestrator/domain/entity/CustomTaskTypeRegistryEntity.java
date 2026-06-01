package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;

/**
 * {@code batch.custom_task_type_registry} 行类型(SDK Phase 3 M3.1,V159)。
 *
 * <p>租户 worker 在 register 时上报的自定义 taskType 描述符落库行。{@code descriptor} 为 JSONB 全文(以 {@code ::text}
 * 读出),顶层列只提查询/索引需要的字段。派单合并(ORCH-P3-2b)与 console 表单渲染(API-P3-1)读本表。
 */
public record CustomTaskTypeRegistryEntity(
    Long id,
    String tenantId,
    String taskTypeCode,
    String displayName,
    String descriptor,
    String descriptorVersion,
    String source,
    String declaredByWorkerCode,
    String status,
    Instant firstDeclaredAt,
    Instant lastDeclaredAt,
    Instant createdAt,
    Instant updatedAt) {}
