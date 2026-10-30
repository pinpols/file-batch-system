package io.github.pinpols.batch.trigger.domain.param;

import java.util.List;
import lombok.Builder;

/** 一批 Trigger Outbox 事件的同轮领取条件；成功领取的行共享 claimToken。 */
@Builder
public record TriggerOutboxEventMarkPublishingBatchParam(
    List<Long> ids,
    String publishingStatus,
    String pendingStatus1,
    String pendingStatus2,
    String claimToken) {}
