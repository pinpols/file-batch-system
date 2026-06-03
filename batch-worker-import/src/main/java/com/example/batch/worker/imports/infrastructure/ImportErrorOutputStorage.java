package com.example.batch.worker.imports.infrastructure;

import com.example.batch.common.config.MinioStorageProperties;
import com.example.batch.common.constants.BatchFileConstants;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.imports.domain.ImportBadRecordEntity;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 导入错误输出存储：将坏记录列表序列化为 NDJSON 格式并写入 MinIO， 路径格式为 {@code
 * <ERROR_OUTPUT_PREFIX><tenantId>/<fileId>/<fileId>.error.jsonl}。
 *
 * <p>供 {@link ImportRecordGovernanceService} 在 {@code ErrorSinkType.ERROR_FILE} 或 {@code BOTH}
 * 模式下调用；文件不存在时直接创建，不做幂等校验。
 *
 * <p>⚠5 (2026-05-03): 改流写避免 StringBuilder 双倍峰值. 之前 builder.toString().getBytes(UTF_8) 把整张错误集 UTF-16
 * → UTF-8 串行物化, 1 万 bad records 时 String + byte[] 双副本占内存. 现在 NDJSON 行级流写到 temp file 再
 * putObject(InputStream, knownSize), 与 export 链路对齐, 1 万行内存占用 = 单行 JSON 大小级别.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportErrorOutputStorage {

  private final MinioStorageProperties minioStorageProperties;
  private final MinioClient minioClient;

  public String writeErrorOutput(
      String tenantId, String fileId, List<ImportBadRecordEntity> badRecords) {
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
    Path spool = null;
    try {
      spool = Files.createTempFile("import-error-", ".jsonl");
      try (BufferedWriter writer = Files.newBufferedWriter(spool, StandardCharsets.UTF_8)) {
        for (ImportBadRecordEntity badRecord : badRecords) {
          writer.write(JsonUtils.toJson(badRecord));
          writer.write('\n');
        }
      }
      long size = Files.size(spool);
      try (InputStream in = Files.newInputStream(spool)) {
        minioClient.putObject(
            PutObjectArgs.builder()
                .bucket(minioStorageProperties.getBucket())
                .object(objectKey)
                .stream(in, size, -1)
                .contentType(BatchFileConstants.CONTENT_TYPE_NDJSON)
                .build());
      }
      return objectKey;
    } catch (Exception exception) {
      throw new IllegalStateException("failed to write import error output", exception);
    } finally {
      if (spool != null) {
        try {
          Files.deleteIfExists(spool);
        } catch (IOException ignored) {
          SwallowedExceptionLogger.warn(
              ImportErrorOutputStorage.class, "catch:IOException", ignored);

          // 临时文件清理失败不阻断主路径; OS 会按 /tmp 策略最终回收
        }
      }
    }
  }
}
