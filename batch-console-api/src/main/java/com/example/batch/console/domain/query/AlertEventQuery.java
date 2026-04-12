package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;

public record AlertEventQuery(
    String tenantId, String severity, String status, String alertType, PageRequest pageRequest) {}
