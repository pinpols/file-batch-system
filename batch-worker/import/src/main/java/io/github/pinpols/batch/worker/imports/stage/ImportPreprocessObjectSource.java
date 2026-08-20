package io.github.pinpols.batch.worker.imports.stage;

import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.EncodingUtils;
import io.github.pinpols.batch.common.utils.PrivateTempFiles;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import io.github.pinpols.batch.worker.imports.config.WorkerImportPayloadProperties;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportPayload;
import io.github.pinpols.batch.worker.imports.domain.ImportStage;
import io.github.pinpols.batch.worker.imports.domain.ImportStageResult;
import io.github.pinpols.batch.worker.imports.preprocess.ImportPreprocessException;
import io.github.pinpols.batch.worker.imports.stage.support.ImportStageSupport;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * PREPROCESS 的对象输入适配器。
 *
 * <p>对象路径不是普通的字节读取：它必须先经过租户归属校验，再根据文件大小选择内存、完整 spool 或 range
 * spool。把这些 IO 与分片边界集中在这里，避免 {@link PreprocessStep} 同时承担编解码和对象存储策略。
 */
@Slf4j
final class ImportPreprocessObjectSource {

  private static final String ERROR_CODE_OBJECT_LOAD_FAILED =
      "IMPORT_PREPROCESS_OBJECT_LOAD_FAILED";

  private final PlatformFileRuntimeRepository runtimeRepository;
  private final S3StorageProperties s3StorageProperties;
  private final BatchObjectStore objectStore;
  private final WorkerImportPayloadProperties payloadProperties;

  ImportPreprocessObjectSource(
      PlatformFileRuntimeRepository runtimeRepository,
      S3StorageProperties s3StorageProperties,
      BatchObjectStore objectStore,
      WorkerImportPayloadProperties payloadProperties) {
    this.runtimeRepository = runtimeRepository;
    this.s3StorageProperties = s3StorageProperties;
    this.objectStore = objectStore;
    this.payloadProperties = payloadProperties;
  }

  void assertObjectBelongsToTenant(ImportJobContext context, ImportPayload importPayload) {
    Long fileId =
        runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID));
    Map<String, Object> fileRecord =
        fileId == null ? Map.of() : runtimeRepository.loadFileRecord(context.getTenantId(), fileId);
    Object registeredPath = fileRecord.get("storage_path");
    if (registeredPath == null
        || !importPayload.storagePath().equals(String.valueOf(registeredPath))) {
      throw new ImportPreprocessException(
          "IMPORT_PREPROCESS_OBJECT_FORBIDDEN",
          "import object path not owned by tenant (tenant="
              + context.getTenantId()
              + ", path="
              + importPayload.storagePath()
              + "); refusing to fetch object not registered to this tenant's file_record");
    }
  }

  byte[] resolveRawBytes(
      ImportJobContext context, ImportPayload importPayload, Object templateConfigObject) {
    if (importPayload != null && Texts.hasText(importPayload.contentBase64())) {
      return Base64.getDecoder().decode(importPayload.contentBase64().trim());
    }
    if (importPayload != null && Texts.hasText(importPayload.content())) {
      Charset charset = resolveCharsetForContentBytes(importPayload, templateConfigObject);
      return importPayload.content().getBytes(charset);
    }
    if (importPayload != null && Texts.hasText(importPayload.storagePath())) {
      return downloadObjectBytes(importPayload);
    }
    String raw = context.getRawPayload();
    return raw == null ? new byte[0] : raw.getBytes(StandardCharsets.UTF_8);
  }

  long objectSizeBytes(ImportPayload importPayload) {
    String bucket = bucketFor(importPayload);
    try {
      return objectStore.statSize(bucket, importPayload.storagePath());
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(ImportPreprocessObjectSource.class, "catch:statObject", ex);
      return -1L;
    }
  }

  boolean supportsRangeRead() {
    return objectStore.supportsRangeRead();
  }

  ImportStageResult streamObjectToSpoolAndReturn(
      ImportJobContext context,
      ImportPayload importPayload,
      Map<String, Object> templateConfig,
      Object templateConfigObject) {
    String bucket = bucketFor(importPayload);
    String object = importPayload.storagePath();
    Path spool = null;
    try {
      spool = PrivateTempFiles.createTempFile("batch-preprocess-obj-", ".raw");
      long bytes;
      try (InputStream in = objectStore.get(bucket, object)) {
        bytes =
            Files.copy(EncodingUtils.stripUtf8Bom(in), spool, StandardCopyOption.REPLACE_EXISTING);
      }
      Charset charset = resolveCharset(importPayload, templateConfigObject);
      context.getAttributes().put(PipelineRuntimeKeys.IMPORT_LARGE_TEXT_PATH, spool.toString());
      context.getAttributes().put(PipelineRuntimeKeys.IMPORT_LARGE_TEXT_CHARSET, charset);
      context.setRawPayload("");
      context.getAttributes().remove(PipelineRuntimeKeys.IMPORT_NORMALIZED_PAYLOAD);
      context.getAttributes().remove(PipelineRuntimeKeys.IMPORT_BINARY_PAYLOAD);
      log.info(
          "import preprocess streamed object to spool (no heap buffering): bucket={}, object={},"
              + " bytes={}, spool={}",
          bucket,
          object,
          bytes,
          spool);
      Map<String, Object> fileMetadata = new LinkedHashMap<>();
      fileMetadata.put("preprocessed", Boolean.TRUE);
      String format = resolveFileFormatType(importPayload, templateConfig);
      fileMetadata.put("preprocessFormat", format == null ? "" : format);
      fileMetadata.put("sourceObject", object);
      fileMetadata.put("sourceBytes", bytes);
      ImportStageSupport.updateFileStatusRecoverAware(
          runtimeRepository, context, "PARSING", fileMetadata);
      return ImportStageResult.success(ImportStage.PREPROCESS);
    } catch (Exception ex) {
      deleteQuietly(spool);
      throw objectLoadFailure(bucket, object, ex);
    }
  }

  ImportStageResult streamObjectRangeToSpool(
      ImportJobContext context,
      ImportPayload importPayload,
      Map<String, Object> templateConfig,
      Object templateConfigObject,
      RangeSlice slice) {
    String bucket = bucketFor(importPayload);
    String object = importPayload.storagePath();
    long rawStart = slice.objectBytes() * (slice.partitionNo() - 1) / slice.partitionCount();
    long rawEnd = slice.partitionNo() == slice.partitionCount()
        ? slice.objectBytes()
        : slice.objectBytes() * slice.partitionNo() / slice.partitionCount();
    Path spool = null;
    try {
      spool = PrivateTempFiles.createTempFile(
          "batch-preprocess-obj-p" + slice.partitionNo() + "-", ".raw");
      long keptBytes;
      try (InputStream in = objectStore.getFrom(bucket, object, rawStart);
          OutputStream out = Files.newOutputStream(
              spool,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE)) {
        InputStream source = slice.partitionNo() == 1 ? EncodingUtils.stripUtf8Bom(in) : in;
        keptBytes = copyPartitionRange(source, out, rawEnd - rawStart, slice.partitionNo() > 1);
      }
      context.getAttributes().put(PipelineRuntimeKeys.IMPORT_LARGE_TEXT_PATH, spool.toString());
      context.getAttributes().put(PipelineRuntimeKeys.IMPORT_LARGE_TEXT_CHARSET, slice.charset());
      context.getAttributes().put(PipelineRuntimeKeys.PARTITION_PRESLICED, Boolean.TRUE);
      context.setRawPayload("");
      context.getAttributes().remove(PipelineRuntimeKeys.IMPORT_NORMALIZED_PAYLOAD);
      context.getAttributes().remove(PipelineRuntimeKeys.IMPORT_BINARY_PAYLOAD);
      log.info(
          "import preprocess range-sliced object to spool: bucket={}, object={}, partition={}/{},"
              + " offset={}, sliceBytes={}, keptBytes={}, spool={}",
          bucket,
          object,
          slice.partitionNo(),
          slice.partitionCount(),
          rawStart,
          rawEnd - rawStart,
          keptBytes,
          spool);
      Map<String, Object> fileMetadata = new LinkedHashMap<>();
      fileMetadata.put("preprocessed", Boolean.TRUE);
      String format = resolveFileFormatType(importPayload, templateConfig);
      fileMetadata.put("preprocessFormat", format == null ? "" : format);
      fileMetadata.put("sourceObject", object);
      fileMetadata.put("rangeSlice", slice.partitionNo() + "/" + slice.partitionCount());
      ImportStageSupport.updateFileStatusRecoverAware(
          runtimeRepository, context, "PARSING", fileMetadata);
      return ImportStageResult.success(ImportStage.PREPROCESS);
    } catch (Exception ex) {
      deleteQuietly(spool);
      context.getAttributes().remove(PipelineRuntimeKeys.PARTITION_PRESLICED);
      log.warn(
          "import preprocess range-slice failed, fallback to full stream: object={}, partition={}/{}, err={}",
          object,
          slice.partitionNo(),
          slice.partitionCount(),
          ex.getMessage());
      return streamObjectToSpoolAndReturn(
          context, importPayload, templateConfig, templateConfigObject);
    }
  }

  static boolean canStreamObjectDirect(
      ImportPayload importPayload, Map<String, Object> templateConfig) {
    Map<String, Object> config = templateConfig == null ? Map.of() : templateConfig;
    String format = resolveFileFormatType(importPayload, config);
    if (format != null && isBinaryImportFormat(format)) {
      return false;
    }
    Object pipeline = config.get("preprocess_pipeline");
    if (pipeline != null
        && Texts.hasText(String.valueOf(pipeline))
        && !"[]".equals(String.valueOf(pipeline).trim())) {
      return false;
    }
    return isNoneOrBlank(config.get("compress_type")) && isNoneOrBlank(config.get("encrypt_type"));
  }

  static boolean rangeSliceEligible(
      ImportPayload importPayload,
      Map<String, Object> templateConfig,
      Integer partitionNo,
      Integer partitionCount,
      Charset charset) {
    if (partitionNo == null || partitionCount == null || partitionCount <= 1) {
      return false;
    }
    if (partitionNo < 1 || partitionNo > partitionCount) {
      return false;
    }
    if (!isNewlineSafeCharset(charset)) {
      return false;
    }
    return isRangeSliceableFormat(
        resolveFileFormatType(importPayload, templateConfig), templateConfig);
  }

  static boolean isBinaryImportFormat(String formatType) {
    if (!Texts.hasText(formatType)) {
      return false;
    }
    String normalized = formatType.trim().toUpperCase(Locale.ROOT);
    return "EXCEL".equals(normalized) || "BINARY".equals(normalized);
  }

  static String resolveFileFormatType(
      ImportPayload importPayload, Map<String, Object> templateConfig) {
    if (importPayload != null && Texts.hasText(importPayload.fileFormatType())) {
      return importPayload.fileFormatType();
    }
    Object value = templateConfig == null ? null : templateConfig.get("file_format_type");
    if (value != null && Texts.hasText(String.valueOf(value))) {
      return String.valueOf(value);
    }
    return null;
  }

  static long copyPartitionRange(
      InputStream rawIn, OutputStream out, long sliceLen, boolean skipPartialFirstLine)
      throws IOException {
    BufferedInputStream in = rawIn instanceof BufferedInputStream buffered
        ? buffered
        : new BufferedInputStream(rawIn, 64 * 1024);
    long consumed = 0;
    long written = 0;
    int b;
    if (skipPartialFirstLine) {
      while ((b = in.read()) >= 0) {
        consumed++;
        if (b == '\n') {
          break;
        }
      }
    }
    while (consumed <= sliceLen) {
      int c = in.read();
      if (c < 0) {
        break;
      }
      consumed++;
      out.write(c);
      written++;
      if (c == '\n') {
        continue;
      }
      while ((c = in.read()) >= 0) {
        consumed++;
        out.write(c);
        written++;
        if (c == '\n') {
          break;
        }
      }
      if (c < 0) {
        break;
      }
    }
    return written;
  }

  private byte[] downloadObjectBytes(ImportPayload importPayload) {
    String bucket = bucketFor(importPayload);
    String object = importPayload.storagePath();
    try (InputStream in = objectStore.get(bucket, object)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[64 * 1024];
      long total = 0;
      int read;
      while ((read = in.read(buffer)) >= 0) {
        if (read == 0) {
          continue;
        }
        total += read;
        if (total > payloadProperties.getMaxObjectBytes()) {
          throw new ImportPreprocessException(
              "IMPORT_PREPROCESS_OBJECT_TOO_LARGE",
              "import object exceeds max-object-bytes="
                  + payloadProperties.getMaxObjectBytes()
                  + " (bucket="
                  + bucket
                  + ", object="
                  + object
                  + "); raise batch.worker.import.max-object-bytes or split the file");
        }
        out.write(buffer, 0, read);
      }
      log.info(
          "import preprocess loaded object from storage: bucket={}, object={}, bytes={}",
          bucket,
          object,
          total);
      return out.toByteArray();
    } catch (ImportPreprocessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw objectLoadFailure(bucket, object, ex);
    }
  }

  private Charset resolveCharsetForContentBytes(
      ImportPayload importPayload, Object templateConfigObject) {
    if (templateConfigObject instanceof Map<?, ?> templateConfig) {
      Object charset = templateConfig.get("charset");
      if (charset != null && Texts.hasText(String.valueOf(charset))) {
        return EncodingUtils.resolve(String.valueOf(charset));
      }
    }
    if (importPayload != null && Texts.hasText(importPayload.charset())) {
      return EncodingUtils.resolve(importPayload.charset());
    }
    return StandardCharsets.UTF_8;
  }

  private Charset resolveCharset(ImportPayload importPayload, Object templateConfigObject) {
    if (templateConfigObject instanceof Map<?, ?> templateConfig) {
      Object targetCharset = templateConfig.get("target_charset");
      if (targetCharset != null && Texts.hasText(String.valueOf(targetCharset))) {
        return EncodingUtils.resolve(String.valueOf(targetCharset));
      }
      Object charset = templateConfig.get("charset");
      if (charset != null && Texts.hasText(String.valueOf(charset))) {
        return EncodingUtils.resolve(String.valueOf(charset));
      }
    }
    if (importPayload != null && Texts.hasText(importPayload.targetCharset())) {
      return EncodingUtils.resolve(importPayload.targetCharset());
    }
    if (importPayload != null && Texts.hasText(importPayload.charset())) {
      return EncodingUtils.resolve(importPayload.charset());
    }
    return StandardCharsets.UTF_8;
  }

  private String bucketFor(ImportPayload importPayload) {
    return Texts.hasText(importPayload.storageBucket())
        ? importPayload.storageBucket()
        : s3StorageProperties.getBucket();
  }

  private static boolean isRangeSliceableFormat(String format, Map<String, Object> templateConfig) {
    if (!Texts.hasText(format)) {
      return false;
    }
    String normalized = format.trim().toUpperCase(Locale.ROOT);
    if ("FIXED_WIDTH".equals(normalized) || "FIXEDWIDTH".equals(normalized)) {
      return true;
    }
    if ("DELIMITED".equals(normalized) || "CSV".equals(normalized) || "TSV".equals(normalized)) {
      Object optIn = templateConfig == null ? null : templateConfig.get("partition_range_slice");
      return optIn != null && "true".equalsIgnoreCase(String.valueOf(optIn).trim());
    }
    return false;
  }

  private static boolean isNewlineSafeCharset(Charset charset) {
    return StandardCharsets.UTF_8.equals(charset)
        || StandardCharsets.US_ASCII.equals(charset)
        || StandardCharsets.ISO_8859_1.equals(charset);
  }

  private static boolean isNoneOrBlank(Object value) {
    if (value == null) {
      return true;
    }
    String text = String.valueOf(value).trim();
    return EmptyChecks.isEmpty(text) || "NONE".equalsIgnoreCase(text);
  }

  private static ImportPreprocessException objectLoadFailure(
      String bucket, String object, Exception cause) {
    return new ImportPreprocessException(
        ERROR_CODE_OBJECT_LOAD_FAILED,
        "failed to load import object from storage (bucket="
            + bucket
            + ", object="
            + object
            + "): "
            + cause.getMessage(),
        cause);
  }

  private static void deleteQuietly(Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException ex) {
      SwallowedExceptionLogger.warn(
          ImportPreprocessObjectSource.class, "catch:tempFileCleanup", ex);
    }
  }

  record RangeSlice(Charset charset, long objectBytes, int partitionNo, int partitionCount) {}
}
