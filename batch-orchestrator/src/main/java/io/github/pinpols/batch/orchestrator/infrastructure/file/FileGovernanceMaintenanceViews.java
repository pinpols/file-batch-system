package io.github.pinpols.batch.orchestrator.infrastructure.file;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.Map;

/**
 * 文件治理维护任务使用的固定查询投影。
 *
 * <p>Mapper 仍以 Map 承接 MyBatis 行结果，但只在仓储边界转换一次；清理任务不再依赖列名字符串或未声明的
 * {@code select *} 字段，从而让归档/孤儿会话清理的输入契约可审查。
 */
final class FileGovernanceMaintenanceViews {

  private FileGovernanceMaintenanceViews() {}

  static ArchivedFileCleanupView archivedFile(Map<String, Object> row) {
    if (EmptyChecks.isEmpty(row)) {
      return ArchivedFileCleanupView.absent();
    }
    Long fileId = longValue(row.get("id"));
    String tenantId = textValue(row.get("tenant_id"));
    String storagePath = textValue(row.get("storage_path"));
    String storageType = textValue(row.get("storage_type"));
    String fileStatus = textValue(row.get("file_status"));
    return new ArchivedFileCleanupView(
        fileId, tenantId, storagePath, storageType, fileStatus);
  }

  static OrphanUploadSessionView orphanUploadSession(Map<String, Object> row) {
    if (EmptyChecks.isEmpty(row)) {
      return OrphanUploadSessionView.absent();
    }
    Long fileId = longValue(row.get("id"));
    String tenantId = textValue(row.get("tenant_id"));
    String storageBucket = textValue(row.get("storage_bucket"));
    String storagePath = textValue(row.get("storage_path"));
    return new OrphanUploadSessionView(
        fileId, tenantId, storageBucket, storagePath);
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
