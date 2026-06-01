package com.example.batch.console.domain.ops.dto;

import java.time.Instant;

/**
 * FE 2-C 响应:任务最新心跳进度 / checkpoint(SDK Phase 4 / ORCH-P4-1)。
 *
 * @param taskId 任务 id
 * @param taskStatus 任务状态
 * @param details worker 上报的进度 / checkpoint 快照(已从 JSONB 解析为 JSON;无心跳时为 null)
 * @param heartbeatAt 最近一次带 details 的心跳时间(UTC;无心跳时为 null)
 * @param cancelRequested 平台是否已请求取消
 */
public record TaskHeartbeatDetailsResponse(
    Long taskId, String taskStatus, Object details, Instant heartbeatAt, boolean cancelRequested) {}
