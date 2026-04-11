package com.example.batch.console.domain.entity;

import lombok.Data;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Data
@Table("batch.system_parameter")
public class SystemParameterEntity {

    @Id private Long id;
    private String tenantId;
    private String paramKey;
    private String paramValue;
    private String description;
    private String createdBy;
    private String updatedBy;
    private Instant createdAt;
    private Instant updatedAt;
}
