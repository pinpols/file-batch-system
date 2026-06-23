package io.github.pinpols.batch.worker.core.support;

import io.github.pinpols.batch.common.dto.EffectiveTaskConfig;

/**
 * ADR-046 P2 切片 2.3:批量认领的单项结果。
 *
 * <p>{@code claimed=true} 时 {@code config} 为 effective config 快照(同单条 claim;旧 orchestrator 返空 body
 * 时为占位空 config,非 null);{@code claimed=false} 表示该 task 没领到(被抢/不可领/不存在),{@code config=null}。
 */
public record TaskClaimResult(Long taskId, boolean claimed, EffectiveTaskConfig config) {}
