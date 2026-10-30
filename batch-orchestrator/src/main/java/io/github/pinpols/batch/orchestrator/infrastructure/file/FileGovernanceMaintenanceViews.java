package io.github.pinpols.batch.orchestrator.infrastructure.file;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.domain.view.FileGovernanceViews;

/** 文件治理维护任务使用的固定查询投影。 */
final class FileGovernanceMaintenanceViews {

  private FileGovernanceMaintenanceViews() {}

  static ArchivedFileCleanupView archivedFile(FileGovernanceViews.ArchivedFileCleanupRow row) {
    if (EmptyChecks.isNull(row)) {
      return ArchivedFileCleanupView.absent();
    }
    return new ArchivedFileCleanupView(
        row.id(), row.tenantId(), row.storagePath(), row.storageType(), row.fileStatus());
  }

  static OrphanUploadSessionView orphanUploadSession(
      FileGovernanceViews.OrphanUploadSessionRow row) {
    if (EmptyChecks.isNull(row)) {
      return OrphanUploadSessionView.absent();
    }
    return new OrphanUploadSessionView(
        row.id(), row.tenantId(), row.storageBucket(), row.storagePath());
  }

  private static Long longValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (EmptyChecks.isNull(value)) {
      return null;
    }
    String presenceText = String.valueOf(value);
    if (EmptyChecks.isBlank(presenceText)) {
      return null;
    }
    String rawValue = String.valueOf(value);
    return Long.valueOf(rawValue);
  }

  private static String textValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  record ArchivedFileCleanupView(
      Long fileId, String tenantId, String storagePath, String storageType, String fileStatus) {
    static ArchivedFileCleanupView absent() {
      return new ArchivedFileCleanupView(null, null, null, null, null);
    }
  }

  record OrphanUploadSessionView(
      Long fileId, String tenantId, String storageBucket, String storagePath) {
    static OrphanUploadSessionView absent() {
      return new OrphanUploadSessionView(null, null, null, null);
    }
  }
}
