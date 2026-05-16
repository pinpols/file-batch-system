package com.example.batch.console.web.request.push;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConsolePushUnsubscribeRequest(@NotBlank @Size(max = 1024) String endpoint) {}
