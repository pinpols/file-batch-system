package io.github.pinpols.batch.console.domain.file.view;

import lombok.Builder;

/** 文件模板安全标记查询的固定列投影。 */
@Builder
public record FileTemplateSecurityFlagsView(
    Boolean previewMaskingEnabled,
    Boolean errorLineMaskingEnabled,
    Boolean logMaskingEnabled,
    Boolean contentEncryptionEnabled,
    String encryptionKeyRef,
    Boolean downloadRequiresApproval,
    String maskingRuleSet) {}
