package io.github.pinpols.batch.worker.core.param;

import java.time.LocalDate;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/** 文件登记语句参数；仅 id 由 JDBC 回填，版本号、默认值与 JSON 序列化由仓库提供。 */
@Getter
@Builder(toBuilder = true)
public class PlatformFileInsertParam {
  @Setter
  private Long id;

  private final String tenantId;
  private final String fileCode;
  private final String bizType;
  private final String fileCategory;
  private final String fileName;
  private final String originalFileName;
  private final String fileExt;
  private final String fileFormatType;
  private final String charset;
  private final String mimeType;
  private final Long fileSizeBytes;
  private final String checksumType;
  private final String checksumValue;
  private final String storageType;
  private final String storagePath;
  private final String storageBucket;
  private final String fileVersion;
  private final Integer fileGenerationNo;
  private final String sourceType;
  private final String sourceRef;
  private final String fileStatus;
  private final LocalDate bizDate;
  private final String traceId;
  private final String metadataJson;
}
