package io.github.pinpols.batch.console.application.contract.request.ops;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record UpdateWebhookRequest(
        @NotBlank @Size(max = 1024) String callbackUrl,
        @NotEmpty List<@NotBlank @Size(max = 64) String> eventTypes,
        @Size(max = 256) String secret,
        Boolean enabled) {}
