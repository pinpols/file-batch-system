package io.github.pinpols.batch.console.domain.notification.param;

import io.github.pinpols.batch.console.domain.notification.view.JobPushDelivery;
import java.time.Instant;

public record JobPushDeliveryClaimParam(
    JobPushDelivery row, String token, Instant now, Instant until, int maxAttempts) {

  private static final String CLAIMED_STATUS = "IN_FLIGHT";

  /** 领取意图的目标固定，调用方只能提供原有领取身份和租期。 */
  public String targetStatus() {
    return CLAIMED_STATUS;
  }
}
