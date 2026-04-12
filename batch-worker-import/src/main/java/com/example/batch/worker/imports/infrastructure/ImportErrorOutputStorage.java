package com.example.batch.worker.imports.infrastructure;

import com.example.batch.common.config.MinioStorageProperties;
import com.example.batch.common.constants.BatchFileConstants;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.worker.imports.domain.ImportBadRecord;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class ImportErrorOutputStorage {

  private final MinioStorageProperties minioStorageProperties;
  private final MinioClient minioClient;

  public String writeErrorOutput(String tenantId, String fileId, List<ImportBadRecord> badRecords) {
    if (!StringUtils.hasText(tenantId)
        || !StringUtils.hasText(fileId)
        || badRecords == null
        || badRecords.isEmpty()) {
      return null;
    }
    String objectKey =
        BatchFileConstants.ERROR_OUTPUT_PREFIX
            + tenantId
            + "/"
            + fileId
            + "/"
            + fileId
            + ".error.jsonl";
    StringBuilder builder = new StringBuilder();
    for (ImportBadRecord badRecord : badRecords) {
      builder.append(JsonUtils.toJson(badRecord)).append('\n');
    }
    byte[] bytes = builder.toString().getBytes(StandardCharsets.UTF_8);
    try {
      minioClient.putObject(
          PutObjectArgs.builder()
              .bucket(minioStorageProperties.getBucket())
              .object(objectKey)
              .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
              .contentType(BatchFileConstants.CONTENT_TYPE_NDJSON)
              .build());
      return objectKey;
    } catch (Exception exception) {
      throw new IllegalStateException("failed to write import error output", exception);
    }
  }
}
