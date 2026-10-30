package io.github.pinpols.batch.worker.dispatchs.param;

import java.sql.Timestamp;
import lombok.Builder;

/** 半开探针抢占参数；保留原时间戳绑定及租户、渠道条件。 */
@Builder
public record DispatchChannelHealthProbeClaimParam(
    String tenantId, String channelCode, Timestamp now, Timestamp newNextProbeAt) {}
