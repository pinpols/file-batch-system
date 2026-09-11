package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import java.time.Instant;
import lombok.Builder;

/**
 * 渠道健康快照写入参数对象，用于把 {@link DispatchChannelHealthRepository} 的 {@code upsertSuccess} / {@code
 * upsertFailureAndBump} / {@code recalcBackoff} 三条方法的公共字段统一封装到一个 record。
 *
 * <p>字段按场景复用：
 *
 * <ul>
 *   <li>{@code nextProbeAt}：upsertSuccess 传下次探针时间；upsertFailureAndBump 传首次失败 (INSERT 路径) 的
 *       placeholder 回退时间；recalcBackoff 传按本次失败次数计算出的退避截止时间。
 *   <li>{@code failureThreshold}：仅 failure 路径使用；success 传 {@code 0}（被忽略）。
 *   <li>{@code probeMessage} / {@code probeEvidence}：success / failure 两路都会写入数据库；recalcBackoff 不读取。
 * </ul>
 */
@Builder
record DispatchHealthUpsertCommand(
    String tenantId,
    String channelCode,
    String channelType,
    Instant now,
    Instant nextProbeAt,
    int failureThreshold,
    String probeMessage,
    String probeEvidence) {}
