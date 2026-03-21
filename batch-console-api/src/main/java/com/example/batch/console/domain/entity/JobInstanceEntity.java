package com.example.batch.console.domain.entity;

import lombok.Data;
import java.time.Instant;
import java.time.LocalDate;

@Data
public class JobInstanceEntity {

    private Long id;
    private String tenantId;
    private String jobCode;
    private String instanceNo;
    private LocalDate bizDate;
    private String instanceStatus;
    private String queueCode;
    private String workerGroup;
    private Integer priority;
    private String traceId;
    private Instant startedAt;
    private Instant finishedAt;
}
