package io.github.pinpols.batch.worker.core.infrastructure;

import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.FILE_ID;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.ID;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.TENANT_ID;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.defaultText;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.params;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.toJson;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.toLong;

import io.github.pinpols.batch.common.utils.FileStateMachine;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.mapper.PlatformFileRuntimeMapper;
import java.time.LocalDate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;

/** 文件记录及文件状态的数据访问协作者。 */
@RequiredArgsConstructor
@Slf4j
final class PlatformFileRecordRepository {

  private final PlatformFileRuntimeMapper mapper;

  Map<String, Object> loadFileRecord(String tenantId, Long fileId) {
    if (!Texts.hasText(tenantId) || fileId == null) {
      return Map.of();
    }
    Map<String, Object> row = mapper.selectFileRecord(params(TENANT_ID, tenantId, FILE_ID, fileId));
    return row == null ? Map.of() : row;
  }

  boolean existsFileRecordByStoragePath(String tenantId, String storageBucket, String storagePath) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(storagePath)) {
      return false;
    }
    Long count =
        mapper.countFileRecordByStoragePath(
            params(
                TENANT_ID, tenantId, "storageBucket", storageBucket, "storagePath", storagePath));
    return count != null && count > 0;
  }

  Map<String, Object> loadFileRecordByStoragePath(
      String tenantId, String storageBucket, String storagePath) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(storagePath)) {
      return Map.of();
    }
    Map<String, Object> row =
        mapper.selectFileRecordByStoragePath(
            params(
                TENANT_ID, tenantId, "storageBucket", storageBucket, "storagePath", storagePath));
    return row == null ? Map.of() : row;
  }

  Long createFileRecord(FileRecordParam param) {
    String tenantId = param.getTenantId();
    String fileCode = param.getFileCode();
    String fileCategory = param.getFileCategory();
    String fileName = param.getFileName();
    String fileFormatType = param.getFileFormatType();
    String storageType = param.getStorageType();
    String storagePath = param.getStoragePath();
    String sourceType = param.getSourceType();
    String fileStatus = param.getFileStatus();
    if (!Texts.hasText(tenantId)
        || !Texts.hasText(fileCategory)
        || !Texts.hasText(fileName)
        || !Texts.hasText(fileFormatType)
        || !Texts.hasText(storageType)
        || !Texts.hasText(storagePath)
        || !Texts.hasText(sourceType)
        || !Texts.hasText(fileStatus)) {
      return null;
    }
    FileStateMachine.assertInitialStatus(fileStatus);
    int nextGenerationNo = 1;
    if (Texts.hasText(fileCode)) {
      Integer maxGeneration =
          mapper.selectMaxFileGenerationNo(params(TENANT_ID, tenantId, "fileCode", fileCode));
      nextGenerationNo = (maxGeneration == null ? 0 : maxGeneration) + 1;
      mapper.markHistoricalFileNotLatest(params(TENANT_ID, tenantId, "fileCode", fileCode));
    }
    String checksumValue = param.getChecksumValue();
    String storageBucket = param.getStorageBucket();
    String fileVersion =
        Texts.hasText(param.getFileVersion()) ? param.getFileVersion() : "v" + nextGenerationNo;
    LocalDate bizDate = param.getBizDate();
    Map<String, Object> values =
        params(
            TENANT_ID,
            tenantId,
            "fileCode",
            fileCode,
            "bizType",
            param.getBizType(),
            "fileCategory",
            fileCategory,
            "fileName",
            fileName,
            "originalFileName",
            param.getOriginalFileName(),
            "fileExt",
            resolveFileExt(fileName),
            "fileFormatType",
            fileFormatType,
            "charset",
            param.getCharset(),
            "mimeType",
            resolveMimeType(fileFormatType),
            "fileSizeBytes",
            Math.max(param.getFileSizeBytes(), 0L),
            "checksumType",
            defaultText(param.getChecksumType(), "NONE"),
            "checksumValue",
            checksumValue,
            "storageType",
            storageType,
            "storagePath",
            storagePath,
            "storageBucket",
            storageBucket,
            "fileVersion",
            fileVersion,
            "fileGenerationNo",
            nextGenerationNo,
            "sourceType",
            sourceType,
            "sourceRef",
            param.getSourceRef(),
            "fileStatus",
            fileStatus,
            "bizDate",
            bizDate,
            "traceId",
            param.getTraceId(),
            "metadataJson",
            toJson(param.getMetadata()));

    // 无 checksum 的重试先按存储路径复用既有记录，避免唯一约束冲突后事务进入 aborted 状态。
    if (!Texts.hasText(checksumValue)) {
      Map<String, Object> existing =
          mapper.selectFileRecordByStoragePath(
              params(
                  TENANT_ID, tenantId, "storageBucket", storageBucket, "storagePath", storagePath));
      if (existing != null && existing.get(ID) != null) {
        Object existingChecksumValue = existing.get("checksum_value");
        String existingChecksum =
            existingChecksumValue == null ? null : existingChecksumValue.toString();
        if (existingChecksum == null || existingChecksum.isBlank()) {
          log.info(
              "file_record dedup pre-check hit (tenant={} storage_path={}), reuse existing"
                  + " fileId={}",
              tenantId,
              storagePath,
              existing.get(ID));
          return toLong(existing.get(ID));
        }
      }
    }
    try {
      mapper.insertFileRecord(values);
      return toLong(values.get(ID));
    } catch (DuplicateKeyException ex) {
      if (!Texts.hasText(checksumValue)) {
        log.warn(
            "file_record dedup race lost (tenant={} storage_path={}): another transaction"
                + " inserted concurrently — current task will retry via outer mechanism",
            tenantId,
            storagePath);
      }
      throw ex;
    }
  }

  void updateFileStatus(Long fileId, String fileStatus, Object metadata) {
    if (fileId == null || !Texts.hasText(fileStatus)) {
      return;
    }
    String currentStatus = currentFileStatus(fileId);
    if (!Texts.hasText(currentStatus)) {
      return;
    }
    FileStateMachine.assertTransition(currentStatus, fileStatus);
    mapper.updateFileRecordStatus(
        params(FILE_ID, fileId, "fileStatus", fileStatus, "metadataJson", toJson(metadata)));
  }

  String currentFileStatus(Long fileId) {
    return fileId == null ? null : mapper.selectFileStatus(params(FILE_ID, fileId));
  }

  void updateFileMetadata(Long fileId, Object metadata) {
    if (fileId != null) {
      mapper.updateFileRecordMetadata(params(FILE_ID, fileId, "metadataJson", toJson(metadata)));
    }
  }

  private String resolveFileExt(String fileName) {
    if (!Texts.hasText(fileName) || !fileName.contains(".")) {
      return null;
    }
    return fileName.substring(fileName.lastIndexOf('.') + 1);
  }

  private String resolveMimeType(String fileFormatType) {
    if (!Texts.hasText(fileFormatType)) {
      return "application/octet-stream";
    }
    return switch (fileFormatType) {
      case "JSON" -> "application/json";
      case "XML" -> "application/xml";
      case "EXCEL" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
      default -> "text/plain";
    };
  }
}
