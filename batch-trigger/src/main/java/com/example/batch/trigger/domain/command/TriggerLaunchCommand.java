package com.example.batch.trigger.domain.command;

import com.example.batch.trigger.web.request.TriggerLaunchRequest;

public record TriggerLaunchCommand(
        TriggerLaunchRequest request, String idempotencyKey, String requestId, String traceId) {}
