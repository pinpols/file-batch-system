package com.example.batch.console.domain.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class SystemParameterEntity {

    private Long id;
    private String tenantId;
    private String paramKey;
    private String paramValue;
    private String description;
    private String createdBy;
    private String updatedBy;
    private Instant createdAt;
    private Instant updatedAt;
}
