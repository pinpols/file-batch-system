package com.example.batch.common.model;

import lombok.Data;

import java.time.Instant;

@Data
public abstract class AuditableEntity {

    private String tenantId;
    private String createdBy;
    private String updatedBy;
    private Instant createdAt;
    private Instant updatedAt;
}
