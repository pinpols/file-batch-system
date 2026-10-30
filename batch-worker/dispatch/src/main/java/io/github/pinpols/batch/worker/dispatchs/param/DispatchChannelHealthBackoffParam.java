package io.github.pinpols.batch.worker.dispatchs.param;

import java.sql.Timestamp;
import lombok.Builder;

/** 失败计数匹配时的退避回写参数；不改变原 CAS 与时间戳语义。 */
@Builder
public record DispatchChannelHealthBackoffParam(
    String tenantId,
    String channelCode,
    Timestamp now,
    Timestamp newNextProbeAt,
    int expectedFailures) {}
