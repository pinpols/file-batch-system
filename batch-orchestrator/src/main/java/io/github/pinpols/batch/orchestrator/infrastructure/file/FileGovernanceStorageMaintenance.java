package io.github.pinpols.batch.orchestrator.infrastructure.file;

import io.github.pinpols.batch.common.storage.ObjectNotFoundException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.FileStateMachine;
import io.github.pinpols.batch.orchestrator.config.FileGovernanceProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.file.S3GovernanceStorage.StorageObjectView;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 文件归档、孤儿上传会话清理及对象存储对账协作者。 */
@RequiredArgsConstructor
@Slf4j
final class FileGovernanceStorageMaintenance {

  private static final String SCHEDULER_NAME = "file-governance-scheduler";
  private static final String ACTOR_SYSTEM = "SYSTEM";

  private final FileGovernanceRepository repository;
  private final S3GovernanceStorage storage;
  private final FileGovernanceProperties properties;

  void cleanupArchivedFiles() {
    if (!properties.getArchive().isEnabled()) {
      return;
    }
    Instant cutoff =
        BatchDateTimeSupport.utcNow()
            .minus(properties.getArchive().getRetentionDays(), ChronoUnit.DAYS);
    List<Map<String, Object>> files =
        repository.selectArchivedFilesForCleanup(
            cutoff, properties.getArchive().getCleanupBatchSize());
    for (Map<String, Object> fileRecord : files) {
      cleanupArchivedFile(fileRecord);
    }
  }

  void cleanupOrphanUploadSessions() {
    if (!properties.getUploadSession().isCleanupEnabled()) {
      return;
    }
    List<Map<String, Object>> sessions =
        repository.selectOrphanUploadSessions(
            properties.getUploadSession().getOrphanTtlSeconds(),
            properties.getUploadSession().getCleanupBatchSize());
    if (sessions.isEmpty()) {
      return;
    }
    long cleaned = 0L;
    long skipped = 0L;
    for (Map<String, Object> session : sessions) {
      if (cleanupOrphanUploadSession(session)) {
        cleaned++;
      } else {
        skipped++;
      }
    }
    log.info(
        "orphan upload session cleanup finished: candidates={}, cleaned={}, skipped={},"
            + " ttlSeconds={}",
        sessions.size(),
        cleaned,
        skipped,
        properties.getUploadSession().getOrphanTtlSeconds());
  }

  void reconcileObjectStorage() {
    if (!properties.getReconcile().isEnabled()) {
      return;
    }
    List<StorageObjectView> objects =
        storage.listObjects(
            properties.getReconcile().getPrefix(),
            properties.getReconcile().getBatchSize(),
            properties.getReconcile().isIncludeTemporaryObjects());
    for (StorageObjectView object : objects) {
      reconcileObject(object);
    }
  }

  private void cleanupArchivedFile(Map<String, Object> fileRecord) {
    Long fileId = toLong(fileRecord.get("id"));
    String tenantId = text(fileRecord.get("tenant_id"));
    String storagePath = text(fileRecord.get("storage_path"));
    String storageType = text(fileRecord.get("storage_type"));
    try {
      if (fileId == null || tenantId == null) {
        return;
      }
      if (repository.countActivePipelineInstances(tenantId, fileId) > 0
          || repository.countPendingDispatchRecords(tenantId, fileId) > 0) {
        return;
      }
      if ("S3".equalsIgnoreCase(storageType) || "OSS".equalsIgnoreCase(storageType)) {
        storage.removeObject(storagePath);
      }
      Map<String, Object> cleanupMetadata = new LinkedHashMap<>();
      cleanupMetadata.put("cleanupAt", BatchDateTimeSupport.utcNow().toString());
      cleanupMetadata.put("cleanupReason", "ARCHIVE_RETENTION_EXPIRED");
      String currentStatus = text(fileRecord.get("file_status"));
      FileStateMachine.assertTransition(currentStatus, "DELETED");
      repository.updateFileStatus(tenantId, fileId, currentStatus, "DELETED", cleanupMetadata);
      Map<String, Object> auditDetail = new LinkedHashMap<>();
      auditDetail.put("storagePath", storagePath);
      auditDetail.put("storageType", storageType);
      repository.appendAudit(
          new FileGovernanceRepository.FileAuditCommand(
              tenantId,
              fileId,
              "CLEANUP",
              "SUCCESS",
              new FileGovernanceRepository.FileAuditActor(ACTOR_SYSTEM, SCHEDULER_NAME),
              "cleanup-" + fileId,
              auditDetail));
    } catch (Exception exception) {
      Map<String, Object> auditDetail = new LinkedHashMap<>();
      auditDetail.put("storagePath", storagePath);
      auditDetail.put("errorMessage", exception.getMessage());
      repository.appendAudit(
          new FileGovernanceRepository.FileAuditCommand(
              tenantId,
              fileId,
              "CLEANUP",
              "FAILED",
              new FileGovernanceRepository.FileAuditActor(ACTOR_SYSTEM, SCHEDULER_NAME),
              "cleanup-" + fileId,
              auditDetail));
      log.warn(
          "archived file cleanup failed: fileId={}, error={}",
          fileId,
          exception.getMessage(),
          exception);
    }
  }

  private boolean cleanupOrphanUploadSession(Map<String, Object> fileRecord) {
    Long fileId = toLong(fileRecord.get("id"));
    String tenantId = text(fileRecord.get("tenant_id"));
    String storageBucket = text(fileRecord.get("storage_bucket"));
    String storagePath = text(fileRecord.get("storage_path"));
    if (fileId == null || tenantId == null || storagePath == null) {
      return false;
    }
    try {
      try {
        long sizeBytes = storage.objectSize(storageBucket, storagePath);
        log.info(
            "skip orphan upload session, object uploaded but never confirmed: tenantId={},"
                + " fileId={}, storagePath={}, sizeBytes={}",
            tenantId,
            fileId,
            storagePath,
            sizeBytes);
        return false;
      } catch (ObjectNotFoundException notFound) {
        // 对象确实不存在，继续清理孤儿占位行。
      }
      Map<String, Object> cleanupMetadata = new LinkedHashMap<>();
      cleanupMetadata.put("cleanupAt", BatchDateTimeSupport.utcNow().toString());
      cleanupMetadata.put("cleanupReason", "UPLOAD_SESSION_ORPHAN_EXPIRED");
      FileStateMachine.assertTransition("RECEIVED", "ARCHIVED");
      int archived =
          repository.updateFileStatus(tenantId, fileId, "RECEIVED", "ARCHIVED", cleanupMetadata);
      if (archived <= 0) {
        return false;
      }
      FileStateMachine.assertTransition("ARCHIVED", "DELETED");
      repository.updateFileStatus(tenantId, fileId, "ARCHIVED", "DELETED", null);
      Map<String, Object> auditDetail = new LinkedHashMap<>();
      auditDetail.put("storageBucket", storageBucket);
      auditDetail.put("storagePath", storagePath);
      auditDetail.put("cleanupReason", "UPLOAD_SESSION_ORPHAN_EXPIRED");
      repository.appendAudit(
          new FileGovernanceRepository.FileAuditCommand(
              tenantId,
              fileId,
              "CLEANUP",
              "SUCCESS",
              new FileGovernanceRepository.FileAuditActor(ACTOR_SYSTEM, SCHEDULER_NAME),
              "orphan-upload-" + fileId,
              auditDetail));
      return true;
    } catch (Exception exception) {
      Map<String, Object> auditDetail = new LinkedHashMap<>();
      auditDetail.put("storagePath", storagePath);
      auditDetail.put("errorMessage", exception.getMessage());
      repository.appendAudit(
          new FileGovernanceRepository.FileAuditCommand(
              tenantId,
              fileId,
              "CLEANUP",
              "FAILED",
              new FileGovernanceRepository.FileAuditActor(ACTOR_SYSTEM, SCHEDULER_NAME),
              "orphan-upload-" + fileId,
              auditDetail));
      log.warn(
          "orphan upload session cleanup failed: tenantId={}, fileId={}, error={}",
          tenantId,
          fileId,
          exception.getMessage(),
          exception);
      return false;
    }
  }

  private void reconcileObject(StorageObjectView object) {
    if (object == null || object.objectName() == null || object.objectName().endsWith(".done")) {
      return;
    }
    String tenantId = properties.getReconcile().getDefaultTenantId();
    if (repository.existsFileRecordByStoragePath(tenantId, object.bucket(), object.objectName())) {
      return;
    }
    String fileName =
        object.objectName().contains("/")
            ? object.objectName().substring(object.objectName().lastIndexOf('/') + 1)
            : object.objectName();
    String fileCategory = resolveFileCategory(object.objectName());
    String fileStatus = resolveFileStatus(fileCategory);
    String traceId = "reconcile-" + sanitizeTrace(fileName);
    Long fileId =
        repository.createReconciledFileRecord(
            new FileGovernanceRepository.ReconciledFileRecordCommand(
                new FileGovernanceRepository.FileIdentity(
                    tenantId, fileCategory, fileName, resolveFileFormatType(fileName)),
                object.size(),
                new FileGovernanceRepository.FileStorage(
                    "S3", object.objectName(), object.bucket()),
                ACTOR_SYSTEM,
                fileStatus,
                traceId,
                buildReconcileMetadata(object)));
    repository.appendAudit(
        new FileGovernanceRepository.FileAuditCommand(
            tenantId,
            fileId,
            "RECONCILE_REGISTER",
            "SUCCESS",
            new FileGovernanceRepository.FileAuditActor(ACTOR_SYSTEM, SCHEDULER_NAME),
            traceId,
            Map.of("bucket", object.bucket(), "storagePath", object.objectName())));
  }

  private Object buildReconcileMetadata(StorageObjectView object) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("reconciled", true);
    metadata.put("etag", object.etag());
    metadata.put("lastModified", object.lastModified());
    return metadata;
  }

  private String resolveFileCategory(String objectName) {
    if (objectName.startsWith("archive/")) {
      return "ARCHIVE";
    }
    if (objectName.startsWith("outbound/")) {
      return "OUTPUT";
    }
    return "INPUT";
  }

  private String resolveFileStatus(String fileCategory) {
    return switch (fileCategory) {
      case "ARCHIVE" -> "ARCHIVED";
      case "OUTPUT" -> "GENERATED";
      default -> "RECEIVED";
    };
  }

  private String resolveFileFormatType(String fileName) {
    String lowerName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
    if (lowerName.endsWith(".csv")) {
      return "DELIMITED";
    }
    if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
      return "EXCEL";
    }
    if (lowerName.endsWith(".xml")) {
      return "XML";
    }
    if (lowerName.endsWith(".json")) {
      return "JSON";
    }
    return "BINARY";
  }

  private String sanitizeTrace(String fileName) {
    return fileName == null ? "object" : fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
  }

  private Long toLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value == null) {
      return null;
    }
    String stringValue = String.valueOf(value);
    return stringValue.isBlank() ? null : Long.valueOf(stringValue);
  }

  private String text(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
