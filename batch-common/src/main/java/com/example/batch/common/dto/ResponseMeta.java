package com.example.batch.common.dto;

import java.time.Instant;

public record ResponseMeta(String requestId, String traceId, Instant timestamp) {}
