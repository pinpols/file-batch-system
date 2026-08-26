package io.github.pinpols.batch.trigger.domain;

import java.time.Instant;

/**
 * Trigger launch 的可查询结果。
 *
 * <p>该投影故意不暴露幂等键和原始请求参数；调用方使用自己持有的租户与幂等键查询，
 * 只得到确认异步接收、派发以及实例关联所需的最小状态。
 */
public record TriggerLaunchStatus(
    String requestId,
    String traceId,
    String requestStatus,
    Long relatedJobInstanceId,
    String instanceStatus,
    Instant updatedAt) {}
