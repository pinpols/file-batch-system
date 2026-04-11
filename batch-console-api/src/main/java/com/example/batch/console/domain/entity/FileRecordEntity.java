package com.example.batch.console.domain.entity;

import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;

@Data
public class FileRecordEntity {

    private Long id;
    private String tenantId;
    private String bizType;
    private String fileName;
    private String fileStatus;
    private LocalDate bizDate;
    private String traceId;
    private Instant createdAt;
    private Instant updatedAt;
}
