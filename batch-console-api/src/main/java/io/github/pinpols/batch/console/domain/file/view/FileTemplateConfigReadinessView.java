package io.github.pinpols.batch.console.domain.file.view;

import lombok.Builder;

/** 文件模板就绪检查所需的固定列投影。 */
@Builder
public record FileTemplateConfigReadinessView(
    String templateCode,
    String templateType,
    Boolean enabled,
    String defaultQuerySql,
    Object fieldMappings,
    String namingRule) {}
