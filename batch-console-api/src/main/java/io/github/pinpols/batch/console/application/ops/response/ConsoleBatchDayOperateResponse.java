package io.github.pinpols.batch.console.application.ops.response;

/** 批量日治理动作（FREEZE / RELEASE / SKIP / REOPEN / CLOSE）结果。 */
public record ConsoleBatchDayOperateResponse(
    Long batchDayId, String dayStatus, Boolean frozen, Integer releasedLaunchCount) {}
