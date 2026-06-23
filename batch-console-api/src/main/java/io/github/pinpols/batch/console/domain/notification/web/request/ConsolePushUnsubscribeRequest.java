package io.github.pinpols.batch.console.domain.notification.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConsolePushUnsubscribeRequest(@NotBlank @Size(max = 1024) String endpoint) {}
