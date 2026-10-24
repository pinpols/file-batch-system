package io.github.pinpols.batch.console.domain.notification.param;

import io.github.pinpols.batch.console.domain.notification.entity.JobPushDelivery;
import java.time.Instant;

public record JobPushDeliveryClaimParam(
    JobPushDelivery row, String token, Instant now, Instant until, int maxAttempts) {}
