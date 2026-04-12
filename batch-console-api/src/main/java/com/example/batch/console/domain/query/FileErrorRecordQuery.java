package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;

public record FileErrorRecordQuery(
    String tenantId,
    Long fileId,
    String errorStage,
    String errorCode,
    Boolean skipped,
    PageRequest pageRequest) {}
