package io.github.pinpols.batch.console.domain.notification.param;

import io.github.pinpols.batch.console.domain.notification.view.JobPushDelivery;
import java.time.Instant;

public record JobPushDeliveryFinishParam(
    JobPushDelivery row,
    String token,
    String status,
    String error,
    Integer httpStatus,
    Instant nextRetry,
    boolean refundAttempt) {}
