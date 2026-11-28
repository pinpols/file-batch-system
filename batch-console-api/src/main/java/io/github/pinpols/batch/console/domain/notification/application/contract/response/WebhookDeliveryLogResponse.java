package io.github.pinpols.batch.console.domain.notification.application.contract.response;

import java.time.Instant;

import org.springframework.web.util.HtmlUtils;

import lombok.Builder;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.SecretMasking;
import io.github.pinpols.batch.console.domain.notification.entity.WebhookDeliveryLogEntity;

/** Public webhook delivery log view. */
@Builder
public record WebhookDeliveryLogResponse(
        Long id,
        String tenantId,
        Long subscriptionId,
        String eventType,
        String payloadJson,
        Integer httpStatus,
        String responseBody,
        String deliveryStatus,
        Integer attempt,
        Instant nextRetryAt,
        Instant createdAt) {

    /** 回显响应体长度上限，与 webhook_delivery_log.response_body VARCHAR(2048)（V45）一致。 */
    private static final int RESPONSE_BODY_MAX_LENGTH = 2048;

    public static WebhookDeliveryLogResponse from(WebhookDeliveryLogEntity entity) {
        Long id = entity.getId();
        String tenantId = entity.getTenantId();
        Long subscriptionId = entity.getSubscriptionId();
        String eventType = entity.getEventType();
        String payloadJson = entity.getPayloadJson();
        String safePayloadJson = SecretMasking.maskSensitiveJson(payloadJson);
        Integer httpStatus = entity.getHttpStatus();
        String storedResponseBody = entity.getResponseBody();
        String decodedResponseBody = storedResponseBody == null ? null : HtmlUtils.htmlUnescape(storedResponseBody);
        String normalizedResponseBody = ConsoleTextSanitizer.normalize(decodedResponseBody);
        String maskedResponseBody =
                SwallowedExceptionLogger.safeMessage(normalizedResponseBody, RESPONSE_BODY_MAX_LENGTH);
        String responseBody = ConsoleTextSanitizer.safeDisplay(maskedResponseBody, RESPONSE_BODY_MAX_LENGTH);
        String deliveryStatus = entity.getDeliveryStatus();
        Integer attempt = entity.getAttempt();
        Instant nextRetryAt = entity.getNextRetryAt();
        Instant createdAt = entity.getCreatedAt();
        return WebhookDeliveryLogResponse.builder()
                .id(id)
                .tenantId(tenantId)
                .subscriptionId(subscriptionId)
                .eventType(eventType)
                .payloadJson(safePayloadJson)
                .httpStatus(httpStatus)
                .responseBody(responseBody)
                .deliveryStatus(deliveryStatus)
                .attempt(attempt)
                .nextRetryAt(nextRetryAt)
                .createdAt(createdAt)
                .build();
    }
}
