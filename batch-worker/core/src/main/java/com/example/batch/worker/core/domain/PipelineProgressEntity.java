package com.example.batch.worker.core.domain;

import java.time.OffsetDateTime;

/**
 * {@code batch.pipeline_progress} 行模型 — ADR-038 平台 worker 续跑位点。
 *
 * <p>一次 pipeline 实例同 stage 至多一行(UNIQUE(tenant_id, pipeline_instance_id, stage))。 无记录 = 从 0
 * 跑(向后兼容);completed=true 表示该 stage 已整体完成,启动期幂等跳过。
 *
 * @param id 主键
 * @param tenantId 租户隔离
 * @param pipelineInstanceId 关联 batch.pipeline_instance.id
 * @param stage LOAD / GENERATE(详见 {@link
 *     com.example.batch.worker.core.infrastructure.checkpoint.ProcessingStage})
 * @param positionMarker Import=已处理到的行号(字符串化);Export=plugin 的 nextCursor 序列化
 * @param processedCount 已成功处理记录数,与 positionMarker 同事务推进
 * @param completed 该 stage 是否已整体完成
 * @param completedAt 完成时间(completed=false 时为 null)
 * @param createdAt 首次插入时间
 * @param updatedAt 最近 UPSERT 时间
 */
public record PipelineProgressEntity(
    Long id,
    String tenantId,
    Long pipelineInstanceId,
    String stage,
    String positionMarker,
    Long processedCount,
    Boolean completed,
    OffsetDateTime completedAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
