package com.example.batch.worker.core.infrastructure;

import java.time.LocalDate;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class FileRecordParam {
  private final String tenantId;
  private final String fileCode;
  private final String bizType;
  private final String fileCategory;
  private final String fileName;
  private final String originalFileName;
  private final String fileFormatType;
  private final String charset;
  private final long fileSizeBytes;
  private final String checksumType;
  private final String checksumValue;
  private final String storageType;
  private final String storagePath;
  private final String storageBucket;
  private final String fileVersion;
  private final LocalDate bizDate;
  private final String sourceType;
  private final String sourceRef;
  private final String fileStatus;
  private final String traceId;
  private final Object metadata;
}
