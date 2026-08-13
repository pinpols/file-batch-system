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
    return new ArchivedFileCleanupView(
        longValue(row.get("id")),
        textValue(row.get("tenant_id")),
        textValue(row.get("storage_path")),
        textValue(row.get("storage_type")),
        textValue(row.get("file_status")));
  }

  static OrphanUploadSessionView orphanUploadSession(Map<String, Object> row) {
    if (EmptyChecks.isEmpty(row)) {
      return OrphanUploadSessionView.absent();
    }
    return new OrphanUploadSessionView(
        longValue(row.get("id")),
        textValue(row.get("tenant_id")),
        textValue(row.get("storage_bucket")),
        textValue(row.get("storage_path")));
  }

  private static Long longValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (EmptyChecks.isNull(value) || EmptyChecks.isBlank(String.valueOf(value))) {
      return null;
    }
    return Long.valueOf(String.valueOf(value));
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
