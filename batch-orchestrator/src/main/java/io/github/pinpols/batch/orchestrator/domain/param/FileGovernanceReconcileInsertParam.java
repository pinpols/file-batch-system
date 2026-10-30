package io.github.pinpols.batch.orchestrator.domain.param;

import java.time.LocalDate;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/** 对账补录语句参数；仅 id 由 JDBC 回填，冲突未插入时保持为空。 */
@Getter
@Builder
public class FileGovernanceReconcileInsertParam {
  @Setter
  private Long id;

  private final String tenantId;
  private final String fileCategory;
  private final String fileName;
  private final String fileExt;
  private final String fileFormatType;
  private final String mimeType;
  private final Long fileSizeBytes;
  private final String storageType;
  private final String storagePath;
  private final String storageBucket;
  private final String sourceType;
  private final String fileStatus;
  private final LocalDate bizDate;
  private final String traceId;
  private final String metadataJson;
}
