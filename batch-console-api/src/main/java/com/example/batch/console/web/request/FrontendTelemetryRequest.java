package com.example.batch.console.web.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record FrontendTelemetryRequest(
        @NotBlank @Size(max = 50) String app,
        String userId,
        String sessionId,
        @NotEmpty @Size(max = 50) List<@Valid Event> events) {
    public record Event(
            @NotBlank @Size(max = 20) String type,
            @NotBlank @Size(max = 200) String name,
            String ts,
            String page,
            Map<String, Object> props) {}
}
