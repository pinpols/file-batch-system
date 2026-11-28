package io.github.pinpols.batch.orchestrator.application.service.governance;

import io.github.pinpols.batch.orchestrator.domain.view.FileGovernanceViews.DispatchRecordRow;
import io.github.pinpols.batch.orchestrator.domain.view.FileGovernanceViews.FileRecordRow;
import io.github.pinpols.batch.orchestrator.domain.view.FileGovernanceViews.TemplateSecurityRow;

/**
 * 文件治理 Repository 投影的稳定读取视图。
 *
 * <p>底层 MyBatis 直接映射为具名记录；这里只补齐文件缺失与可选安全配置的业务默认值。
 * metadata 等动态 JSON 字段仍保留 Map 边界。
 */
final class FileGovernanceViews {

    private FileGovernanceViews() {}

    static FileRecordView fileRecord(FileRecordRow row) {
        if (row == null) {
            return FileRecordView.absent();
        }
        return new FileRecordView(true, row.fileStatus(), row.storageBucket(), row.storagePath());
    }

    static TemplateSecurityView templateSecurity(TemplateSecurityRow row) {
        boolean previewMaskingEnabled = row != null && Boolean.TRUE.equals(row.previewMaskingEnabled());
        boolean contentEncryptionEnabled = row != null && Boolean.TRUE.equals(row.contentEncryptionEnabled());
        boolean downloadRequiresApproval = row != null && Boolean.TRUE.equals(row.downloadRequiresApproval());
        return new TemplateSecurityView(
                previewMaskingEnabled,
                contentEncryptionEnabled,
                row == null ? null : row.encryptionKeyRef(),
                downloadRequiresApproval,
                row == null ? null : row.maskingRuleSet());
    }

    static DispatchRecordView dispatchRecord(DispatchRecordRow row) {
        if (row == null) {
            return DispatchRecordView.absent();
        }
        return new DispatchRecordView(true, row.id(), row.pipelineInstanceId(), row.channelCode());
    }

    record FileRecordView(boolean present, String fileStatus, String storageBucket, String storagePath) {
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
