package com.example.batch.worker.imports.domain;

import java.time.Instant;
import lombok.Data;

@Data
public class ImportBadRecord {

    private Long recordNo;
    private String stageCode;
    private String errorCode;
    private String errorMessage;
    private Object rawRecord;
    private boolean skipped;
    private String skipAction;
    private Instant createdAt = Instant.now();
}
