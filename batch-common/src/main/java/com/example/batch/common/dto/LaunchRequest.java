package com.example.batch.common.dto;

import com.example.batch.common.enums.TriggerType;
import java.time.LocalDate;
import java.util.Map;
import lombok.Builder;

@Builder
public record LaunchRequest(
    String tenantId,
    String jobCode,
    LocalDate bizDate,
    TriggerType triggerType,
    String requestId,
    String traceId,
    Map<String, Object> params) {}
