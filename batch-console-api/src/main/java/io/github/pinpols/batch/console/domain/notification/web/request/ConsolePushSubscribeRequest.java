package io.github.pinpols.batch.console.domain.notification.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 浏览器 {@code PushSubscription.toJSON()} 字段原样上报。
 *
 * <pre>
 * {
 *   "endpoint": "https://fcm.googleapis.com/...",
 *   "expirationTime": null,
 *   "keys": { "p256dh": "...", "auth": "..." }
 * }
 * </pre>
 */
public record ConsolePushSubscribeRequest(
    @NotBlank @Size(max = 1024) String endpoint, Long expirationTime, Keys keys) {

  public record Keys(
      @NotBlank @Size(max = 256) String p256dh, @NotBlank @Size(max = 64) String auth) {}
}
