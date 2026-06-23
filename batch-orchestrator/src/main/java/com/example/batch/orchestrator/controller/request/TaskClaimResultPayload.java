package com.example.batch.orchestrator.controller.request;

import com.example.batch.common.dto.EffectiveTaskConfig;

/**
 * ADR-046 P2 切片 2.1:批量认领的逐项结果。
 *
 * <p>{@code claimed=true} 时 {@code config} 为该 task 的 effective config 快照(同单条 claim 返回); {@code
 * claimed=false}(被并发对手领走 / READY 窗口已过 / task 不存在)时 {@code config=null}—— 批量里这是**正常项,不抛异常**,worker
 * 只处理领到的子集。
 */
public record TaskClaimResultPayload(Long taskId, boolean claimed, EffectiveTaskConfig config) {}
