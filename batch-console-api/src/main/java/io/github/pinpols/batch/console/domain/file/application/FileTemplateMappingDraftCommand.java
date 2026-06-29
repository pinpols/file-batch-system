package io.github.pinpols.batch.console.domain.file.application;

import java.util.List;

/** 文件模板字段映射草案命令：由 Web DTO 转换而来，避免应用层依赖 Web 层响应模型。 */
public record FileTemplateMappingDraftCommand(
    String tenantId,
    String direction,
    String schemaName,
    String tableName,
    String tenantColumn,
    List<String> conflictColumns,
    Boolean standardAuditBindings,
    String defaultQuerySql,
    List<Field> fields) {

  public record Field(
      String sourceColumn,
      String targetColumn,
      String header,
      String type,
      Boolean required,
      Boolean persist,
      String format) {}
}
