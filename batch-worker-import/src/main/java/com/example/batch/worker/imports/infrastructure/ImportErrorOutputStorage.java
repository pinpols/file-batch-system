package com.example.batch.worker.imports.infrastructure;

import com.example.batch.common.config.MinioStorageProperties;
import com.example.batch.common.constants.BatchFileConstants;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.imports.domain.ImportBadRecord;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 导入错误输出存储：将坏记录列表序列化为 NDJSON 格式并写入 MinIO， 路径格式为 {@code
 * <ERROR_OUTPUT_PREFIX><tenantId>/<fileId>/<fileId>.error.jsonl}。
 *
 * <p>供 {@link ImportRecordGovernanceService} 在 {@code ErrorSinkType.ERROR_FILE} 或 {@code BOTH}
 * 模式下调用；文件不存在时直接创建，不做幂等校验。
 */
@Component
@RequiredArgsConstructor
public class ImportErrorOutputStorage {

  private final MinioStorageProperties minioStorageProperties;
  private final MinioClient minioClient;

  public String writeErrorOutput(String tenantId, String fileId, List<ImportBadRecord> badRecords) {
    if (!Texts.hasText(tenantId)
        || !Texts.hasText(fileId)
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
