package io.github.pinpols.batch.orchestrator.application.service.governance;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.Map;

/**
 * 文件治理 Repository 投影的稳定读取视图。
 *
 * <p>底层 MyBatis 查询仍以 Map 接住历史 {@code SELECT *} 结果；转换只允许发生在应用服务边界，防止列名字符串继续进入状态、下载和重派决策。
 * metadata 等动态 JSON 字段不属于本视图，继续保留 Map 边界。
 */
final class FileGovernanceViews {

  private FileGovernanceViews() {}

  static FileRecordView fileRecord(Map<String, Object> row) {
    if (EmptyChecks.isEmpty(row)) {
      return FileRecordView.absent();
    }
    return new FileRecordView(
        true,
        stringValue(row, "file_status"),
        stringValue(row, "storage_bucket"),
        stringValue(row, "storage_path"));
  }

  static TemplateSecurityView templateSecurity(Map<String, Object> row) {
    return new TemplateSecurityView(
        booleanValue(row, "preview_masking_enabled"),
        booleanValue(row, "content_encryption_enabled"),
        stringValue(row, "encryption_key_ref"),
        booleanValue(row, "download_requires_approval"),
        stringValue(row, "masking_rule_set"));
  }

  static DispatchRecordView dispatchRecord(Map<String, Object> row) {
    if (EmptyChecks.isEmpty(row)) {
      return DispatchRecordView.absent();
    }
    return new DispatchRecordView(
        true,
        longValue(row, "id"),
        longValue(row, "pipeline_instance_id"),
        stringValue(row, "channel_code"));
  }

  private static String stringValue(Map<String, Object> row, String key) {
    Object value = row == null ? null : row.get(key);
    return value == null ? null : value.toString();
  }

  private static Long longValue(Map<String, Object> row, String key) {
    Object value = row == null ? null : row.get(key);
    return value instanceof Number number
        ? number.longValue()
        : value == null ? null : Long.valueOf(value.toString());
  }

  private static boolean booleanValue(Map<String, Object> row, String key) {
    Object value = row == null ? null : row.get(key);
    return value instanceof Boolean bool
        ? bool
        : value != null && Boolean.parseBoolean(value.toString());
  }

  record FileRecordView(
      boolean present, String fileStatus, String storageBucket, String storagePath) {
    static FileRecordView absent() {
      return new FileRecordView(false, null, null, null);
    }
  }

  record TemplateSecurityView(
      boolean previewMaskingEnabled,
      boolean contentEncryptionEnabled,
      String encryptionKeyRef,
      boolean downloadRequiresApproval,
      String maskingRuleSet) {}

  record DispatchRecordView(boolean present, Long id, Long pipelineInstanceId, String channelCode) {
    static DispatchRecordView absent() {
      return new DispatchRecordView(false, null, null, null);
    }
  }
}
