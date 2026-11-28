package io.github.pinpols.batch.console.domain.notification.param;

import java.time.Instant;

import io.github.pinpols.batch.console.domain.notification.view.JobPushDelivery;

public record JobPushDeliveryFinishParam(
        JobPushDelivery row,
        String token,
        String status,
        String error,
        Integer httpStatus,
        Instant nextRetry,
        boolean refundAttempt) {}
