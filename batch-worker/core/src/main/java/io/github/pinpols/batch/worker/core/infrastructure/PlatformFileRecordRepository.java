package io.github.pinpols.batch.worker.core.infrastructure;

import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.defaultText;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.toJson;

import io.github.pinpols.batch.common.constants.BatchFileConstants;
import io.github.pinpols.batch.common.enums.DictEnum;
import io.github.pinpols.batch.common.enums.FileTemplateFormat;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.FileStateMachine;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.mapper.PlatformFileRecordView;
import io.github.pinpols.batch.worker.core.mapper.PlatformFileRuntimeMapper;
import io.github.pinpols.batch.worker.core.param.PlatformFileInsertParam;
import java.time.LocalDate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 文件记录及文件状态的数据访问协作者。 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class PlatformFileRecordRepository {

  private final PlatformFileRuntimeMapper mapper;

  public Map<String, Object> loadFileRecord(String tenantId, Long fileId) {
    if (!Texts.hasText(tenantId) || fileId == null) {
      return Map.of();
    }
    PlatformFileRecordView row = mapper.selectFileRecord(tenantId, fileId);
    return EmptyChecks.isNull(row) ? Map.of() : row.toPluginValues();
  }

  public boolean existsFileRecordByStoragePath(
      String tenantId, String storageBucket, String storagePath) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(storagePath)) {
      return false;
    }
    Long count = mapper.countFileRecordByStoragePath(tenantId, storageBucket, storagePath);
    return count != null && count > 0;
  }

  public Map<String, Object> loadFileRecordByStoragePath(
      String tenantId, String storageBucket, String storagePath) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(storagePath)) {
      return Map.of();
    }
    PlatformFileRecordView row =
        mapper.selectFileRecordByStoragePath(tenantId, storageBucket, storagePath);
    return EmptyChecks.isNull(row) ? Map.of() : row.toPluginValues();
  }

  @Transactional
  public Long createFileRecord(FileRecordParam param) {
    String tenantId = param.getTenantId();
    String fileCode = param.getFileCode();
    String fileCategory = param.getFileCategory();
    String fileName = param.getFileName();
    String fileFormatType = param.getFileFormatType();
    String storageType = param.getStorageType();
    String storagePath = param.getStoragePath();
    String sourceType = param.getSourceType();
    String fileStatus = param.getFileStatus();
    boolean fileRecordHasMissingRequiredFields = !Texts.hasText(tenantId)
        || !Texts.hasText(fileCategory)
        || !Texts.hasText(fileName)
        || !Texts.hasText(fileFormatType)
        || !Texts.hasText(storageType)
        || !Texts.hasText(storagePath)
        || !Texts.hasText(sourceType)
        || !Texts.hasText(fileStatus);
    if (fileRecordHasMissingRequiredFields) {
      return null;
    }
    FileStateMachine.assertInitialStatus(fileStatus);
    int nextGenerationNo = 1;
    if (Texts.hasText(fileCode)) {
      // 先锁逻辑身份，再以独立查询取得锁等待后的新快照；已有行的更新锁无法保护 MAX+1。
      mapper.lockFileGeneration(tenantId, fileCode);
      Integer maxGeneration = mapper.selectMaxFileGenerationNo(tenantId, fileCode);
      nextGenerationNo = (maxGeneration == null ? 0 : maxGeneration) + 1;
    }
    String checksumValue = param.getChecksumValue();
    String storageBucket = param.getStorageBucket();
    String fileVersion =
        Texts.hasText(param.getFileVersion()) ? param.getFileVersion() : "v" + nextGenerationNo;
    LocalDate bizDate = param.getBizDate();
    String bizType = param.getBizType();
    String originalFileName = param.getOriginalFileName();
    String fileExt = resolveFileExt(fileName);
    String charset = param.getCharset();
    String mimeType = resolveMimeType(fileFormatType);
    long fileSizeBytes = Math.max(param.getFileSizeBytes(), 0L);
    String checksumType = defaultText(param.getChecksumType(), "NONE");
    String sourceRef = param.getSourceRef();
    String traceId = param.getTraceId();
    String metadataJson = toJson(param.getMetadata());
    PlatformFileInsertParam values = PlatformFileInsertParam.builder()
        .tenantId(tenantId)
        .fileCode(fileCode)
        .bizType(bizType)
        .fileCategory(fileCategory)
        .fileName(fileName)
        .originalFileName(originalFileName)
        .fileExt(fileExt)
        .fileFormatType(fileFormatType)
        .charset(charset)
        .mimeType(mimeType)
        .fileSizeBytes(fileSizeBytes)
        .checksumType(checksumType)
        .checksumValue(checksumValue)
        .storageType(storageType)
        .storagePath(storagePath)
        .storageBucket(storageBucket)
        .fileVersion(fileVersion)
        .fileGenerationNo(nextGenerationNo)
        .sourceType(sourceType)
        .sourceRef(sourceRef)
        .fileStatus(fileStatus)
        .bizDate(bizDate)
        .traceId(traceId)
        .metadataJson(metadataJson)
        .build();

    // 无 checksum 的重试先按存储路径复用既有记录，避免唯一约束冲突后事务进入 aborted 状态。
    if (!Texts.hasText(checksumValue)) {
      PlatformFileRecordView existing =
          mapper.selectFileRecordByStoragePath(tenantId, storageBucket, storagePath);
      if (EmptyChecks.isNotNull(existing) && EmptyChecks.isNotNull(existing.id())) {
        String existingChecksum = existing.checksumValue();
        if (existingChecksum == null || existingChecksum.isBlank()) {
          log.info(
              "file_record dedup pre-check hit (tenant={} storage_path={}), reuse existing"
                  + " fileId={}",
              tenantId,
              storagePath,
              existing.id());
          return existing.id();
        }
      }
    }
    // 复用已有文件不会产生新版本，不能取消当前最新标记；只在确实要插入时更新历史版本。
    if (Texts.hasText(fileCode)) {
      mapper.markHistoricalFileNotLatest(tenantId, fileCode);
    }
    try {
      mapper.insertFileRecord(values);
      return values.getId();
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

  public void updateFileStatus(Long fileId, String fileStatus, Object metadata) {
    if (fileId == null || !Texts.hasText(fileStatus)) {
      return;
    }
    String currentStatus = currentFileStatus(fileId);
    if (!Texts.hasText(currentStatus)) {
      return;
    }
    FileStateMachine.assertTransition(currentStatus, fileStatus);
    String metadataJson = toJson(metadata);
    mapper.updateFileRecordStatus(fileId, fileStatus, metadataJson);
  }

  public String currentFileStatus(Long fileId) {
    return EmptyChecks.isNull(fileId) ? null : mapper.selectFileStatus(fileId);
  }

  public void updateFileMetadata(Long fileId, Object metadata) {
    if (fileId != null) {
      String metadataJson = toJson(metadata);
      mapper.updateFileRecordMetadata(fileId, metadataJson);
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
      return BatchFileConstants.CONTENT_TYPE_OCTET_STREAM;
    }
    FileTemplateFormat format = DictEnum.fromCode(FileTemplateFormat.class, fileFormatType);
    if (EmptyChecks.isNull(format)) {
      return BatchFileConstants.CONTENT_TYPE_TEXT_UTF8;
    }
    return switch (format) {
      case JSON -> BatchFileConstants.CONTENT_TYPE_JSON;
      case XML -> BatchFileConstants.CONTENT_TYPE_XML;
      case EXCEL -> BatchFileConstants.CONTENT_TYPE_EXCEL;
      default -> BatchFileConstants.CONTENT_TYPE_TEXT_UTF8;
    };
  }
}
