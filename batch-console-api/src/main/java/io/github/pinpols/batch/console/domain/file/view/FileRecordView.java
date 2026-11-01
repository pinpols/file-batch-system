package io.github.pinpols.batch.console.domain.file.view;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;

@Data
public class FileRecordView {

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
