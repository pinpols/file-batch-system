package io.github.pinpols.batch.console.domain.file.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.plugin.WorkerPluginIds;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.domain.file.application.FileTemplateMappingDraftCommand;
import io.github.pinpols.batch.console.domain.file.application.FileTemplateMappingDraftResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 构造文件模板导入和导出映射草稿。 */
final class FileTemplateMappingDraftBuilder {
  private FileTemplateMappingDraftBuilder() {}

  static FileTemplateMappingDraftResult draftImportMapping(
      ObjectMapper objectMapper, FileTemplateMappingDraftCommand command) {
    List<String> warnings = new ArrayList<>();
    List<Map<String, Object>> fieldMappings = new ArrayList<>();
    List<Map<String, Object>> columnMappings = new ArrayList<>();
    for (FileTemplateMappingDraftCommand.Field field : fieldsOf(command)) {
      if (EmptyChecks.isNull(field)) {
        warnings.add("存在空字段配置,已跳过");
        continue;
      }
      String sourceColumn = firstText(field.sourceColumn(), field.targetColumn());
      String targetColumn = firstText(field.targetColumn(), sourceColumn);
      if (!Texts.hasText(sourceColumn)) {
        warnings.add("存在未填写 sourceColumn/targetColumn 的字段,已跳过");
        continue;
      }
      Map<String, Object> mapping = new LinkedHashMap<>();
      mapping.put("name", sourceColumn);
      putIfText(mapping, "targetColumn", targetColumn);
      putIfText(mapping, "type", field.type());
      putIfNotNull(mapping, "required", field.required());
      putIfNotNull(mapping, "persist", field.persist());
      putIfText(mapping, "format", field.format());
      fieldMappings.add(mapping);

      if (!Boolean.FALSE.equals(field.persist()) && Texts.hasText(targetColumn)) {
        Map<String, Object> columnMapping = new LinkedHashMap<>();
        columnMapping.put("from", sourceColumn);
        columnMapping.put("to", targetColumn);
        columnMappings.add(columnMapping);
      }
    }

    Map<String, Object> jdbcMappedImport = new LinkedHashMap<>();
    putIfText(jdbcMappedImport, "schema", command.schemaName());
    putIfText(jdbcMappedImport, "table", command.tableName());
    jdbcMappedImport.put("tenantColumn", firstText(command.tenantColumn(), "tenant_id"));
    if (EmptyChecks.isNotEmpty(columnMappings)) {
      jdbcMappedImport.put("columnMappings", columnMappings);
    }
    if (EmptyChecks.isNotEmpty(command.conflictColumns())) {
      jdbcMappedImport.put("conflictColumns", command.conflictColumns());
    }
    if (Boolean.TRUE.equals(command.standardAuditBindings())) {
      jdbcMappedImport.put("standardAuditBindings", true);
    }
    if (!Texts.hasText(command.tableName())) {
      warnings.add("未填写 tableName,保存前需要补齐 jdbcMappedImport.table");
    }
    Map<String, Object> queryParamSchema = new LinkedHashMap<>();
    queryParamSchema.put("jdbcMappedImport", jdbcMappedImport);
    String fieldMappingsJson = writeJson(objectMapper, fieldMappings);
    String queryParamSchemaJson = writeJson(objectMapper, queryParamSchema);
    String defaultQuerySql = blankToNull(command.defaultQuerySql());
    return new FileTemplateMappingDraftResult(
        "IMPORT", fieldMappingsJson, queryParamSchemaJson, defaultQuerySql, warnings);
  }

  static FileTemplateMappingDraftResult draftExportMapping(
      ObjectMapper objectMapper, FileTemplateMappingDraftCommand command) {
    List<String> warnings = new ArrayList<>();
    List<Map<String, Object>> fieldMappings = new ArrayList<>();
    List<Map<String, Object>> csvColumns = new ArrayList<>();
    List<String> selectedColumns = new ArrayList<>();
    for (FileTemplateMappingDraftCommand.Field field : fieldsOf(command)) {
      if (EmptyChecks.isNull(field)) {
        warnings.add("存在空字段配置,已跳过");
        continue;
      }
      String sourceColumn = firstText(field.sourceColumn(), field.targetColumn());
      if (!Texts.hasText(sourceColumn)) {
        warnings.add("存在未填写 sourceColumn/targetColumn 的字段,已跳过");
        continue;
      }
      Map<String, Object> mapping = new LinkedHashMap<>();
      mapping.put("sourceColumn", sourceColumn);
      mapping.put("header", firstText(field.header(), sourceColumn));
      putIfText(mapping, "type", field.type());
      putIfText(mapping, "format", field.format());
      fieldMappings.add(mapping);
      Map<String, Object> csvColumn = new LinkedHashMap<>();
      csvColumn.put("header", firstText(field.header(), sourceColumn));
      csvColumn.put("source", "detail." + sourceColumn);
      csvColumns.add(csvColumn);
      selectedColumns.add(sourceColumn);
    }

    Map<String, Object> sqlTemplateExport = new LinkedHashMap<>();
    putIfText(sqlTemplateExport, "schema", command.schemaName());
    putIfText(sqlTemplateExport, "table", command.tableName());
    if (EmptyChecks.isNotEmpty(selectedColumns)) {
      sqlTemplateExport.put("columns", selectedColumns);
    }
    sqlTemplateExport.put("cursorColumn", "id");
    Map<String, Object> queryParamSchema = new LinkedHashMap<>();
    queryParamSchema.put("export_data_ref", WorkerPluginIds.EXPORT_DATA_SQL_TEMPLATE);
    queryParamSchema.put("sqlTemplateExport", sqlTemplateExport);
    if (EmptyChecks.isNotEmpty(csvColumns)) {
      // Worker 的文件列来自 csvColumns；SQL 游标列只服务分页，不应自动进入文件。
      queryParamSchema.put("csvColumns", csvColumns);
    }

    String defaultQuerySql = blankToNull(command.defaultQuerySql());
    if (EmptyChecks.isNull(defaultQuerySql)) {
      warnings.add("请填写实际业务导出 SQL，按 :tenantId 和 :batchNo 过滤租户及批次，并返回分页游标列");
    }
    String fieldMappingsJson = writeJson(objectMapper, fieldMappings);
    String queryParamSchemaJson = writeJson(objectMapper, queryParamSchema);
    return new FileTemplateMappingDraftResult(
        "EXPORT", fieldMappingsJson, queryParamSchemaJson, defaultQuerySql, warnings);
  }

  private static String writeJson(ObjectMapper objectMapper, Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw BizException.of(
          ResultCode.SYSTEM_ERROR,
          ResultCode.SYSTEM_ERROR.detailKey(),
          "file template json draft failed");
    }
  }

  private static List<FileTemplateMappingDraftCommand.Field> fieldsOf(
      FileTemplateMappingDraftCommand command) {
    return EmptyChecks.isNull(command.fields()) ? List.of() : command.fields();
  }

  private static void putIfText(Map<String, Object> map, String key, String value) {
    if (Texts.hasText(value)) {
      map.put(key, value.trim());
    }
  }

  private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
    if (EmptyChecks.isNotNull(value)) {
      map.put(key, value);
    }
  }

  private static String firstText(String preferred, String fallback) {
    return Texts.hasText(preferred) ? preferred.trim() : blankToNull(fallback);
  }

  static String blankToNull(String value) {
    return Texts.hasText(value) ? value.trim() : null;
  }
}
