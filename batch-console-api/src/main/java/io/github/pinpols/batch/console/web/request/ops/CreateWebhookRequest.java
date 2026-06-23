package io.github.pinpols.batch.console.web.request.ops;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateWebhookRequest(
    @NotBlank @Size(max = 128) String name,
    @NotBlank @Size(max = 1024) String callbackUrl,
    @NotEmpty List<@NotBlank @Size(max = 64) String> eventTypes,
    @Size(max = 256) String secret,
    Boolean enabled) {}
