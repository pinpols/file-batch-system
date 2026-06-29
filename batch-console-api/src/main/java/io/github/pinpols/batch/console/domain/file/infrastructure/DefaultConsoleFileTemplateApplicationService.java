package io.github.pinpols.batch.console.domain.file.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.plugin.WorkerPluginIds;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.domain.file.application.ConsoleFileTemplateApplicationService;
import io.github.pinpols.batch.console.domain.file.application.FileTemplateMappingDraftCommand;
import io.github.pinpols.batch.console.domain.file.application.FileTemplateMappingDraftResult;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.file.param.FileTemplateConfigUpsertParam;
import io.github.pinpols.batch.console.domain.file.query.FileTemplateConfigQuery;
import io.github.pinpols.batch.console.domain.file.web.query.FileTemplateQueryRequest;
import io.github.pinpols.batch.console.domain.file.web.request.FileTemplateCreateRequest;
import io.github.pinpols.batch.console.domain.file.web.request.FileTemplateUpdateRequest;
import io.github.pinpols.batch.console.domain.job.infrastructure.DefaultConsoleJobDefinitionApplicationService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 文件模板配置的 CRUD：list / get / create / update / toggle。
 *
 * <p>唯一键：{@code (tenantId, templateCode, version)}——允许同 code 多版本并存，用于灰度发布或滚动升级。 create 时若同 (code,
 * version) 已存在抛 {@code CONFLICT}；update 默认沿用现有 version 但允许入参覆盖， 便于"就地升版"或"平移到新版号"两种语义。
 *
 * <p>与 {@link DefaultConsoleJobDefinitionApplicationService} 不同，本类不调 cache invalidation—— 文件模板不在
 * orchestrator launch 热路径上读取（由 worker 导入/导出阶段按需拉取），无需前置失效 Redis 缓存。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleFileTemplateApplicationService
    implements ConsoleFileTemplateApplicationService {

  private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  private final FileTemplateConfigMapper mapper;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final ObjectMapper objectMapper;

  @Override
  public PageResponse<Map<String, Object>> list(FileTemplateQueryRequest request) {
    String tenantId = resolveTenant(request.getTenantId());
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    FileTemplateConfigQuery query =
        new FileTemplateConfigQuery(
            tenantId,
            request.getKeyword(),
            request.getTemplateCode(),
            request.getTemplateName(),
            request.getTemplateType(),
            request.getBizType(),
            request.getEnabled(),
            pageRequest);
    long total = mapper.countByQuery(query);
    List<Map<String, Object>> items = mapper.selectByQuery(query);
    return new PageResponse<>(total, pageRequest.pageNo(), pageRequest.pageSize(), items);
  }

  @Override
  public Map<String, Object> get(Long id, String tenantId) {
    String resolved = resolveTenant(tenantId);
    return Guard.requireFound(mapper.selectById(resolved, id), "file template not found: " + id);
  }

  @Override
  public Map<String, Object> create(FileTemplateCreateRequest request) {
    String tenantId = resolveTenant(request.getTenantId());
    int version = request.getVersion() != null ? request.getVersion() : 1;
    Map<String, Object> existing =
        mapper.selectByUniqueKey(tenantId, request.getTemplateCode(), version);
    if (existing != null) {
      throw BizException.of(
          ResultCode.CONFLICT,
          "error.common.conflict_detail",
          "template code + version already exists: " + request.getTemplateCode() + "/" + version);
    }
    String operator = requestMetadataResolver.current().operatorId();
    mapper.upsertFileTemplateConfig(buildCreateParam(tenantId, version, operator, request));
    return mapper.selectByUniqueKey(tenantId, request.getTemplateCode(), version);
  }

  @Override
  public Map<String, Object> update(Long id, FileTemplateUpdateRequest request) {
    String tenantId = resolveTenant(request.getTenantId());
    Map<String, Object> existing =
        Guard.requireFound(mapper.selectById(tenantId, id), "file template not found: " + id);
    String operator = requestMetadataResolver.current().operatorId();
    String templateCode = (String) existing.get("template_code");
    int version =
        request.getVersion() != null
            ? request.getVersion()
            : existing.get("version") != null ? ((Number) existing.get("version")).intValue() : 1;
    mapper.upsertFileTemplateConfig(
        buildUpdateParam(tenantId, templateCode, version, operator, request, existing));
    return mapper.selectById(tenantId, id);
  }

  @Override
  public void toggle(Long id, String tenantId, Boolean enabled) {
    String resolved = resolveTenant(tenantId);
    int rows = mapper.toggleEnabled(resolved, id, enabled);
    if (rows == 0) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.file_template.not_found", id);
    }
  }

  @Override
  public FileTemplateMappingDraftResult draftMapping(FileTemplateMappingDraftCommand command) {
    resolveTenant(command.tenantId());
    String direction = normalizeDirection(command.direction());
    return "EXPORT".equals(direction) ? draftExportMapping(command) : draftImportMapping(command);
  }

  private String resolveTenant(String tenantId) {
    return tenantGuard.resolveTenant(tenantId);
  }

  private FileTemplateConfigUpsertParam buildCreateParam(
      String tenantId, int version, String operator, FileTemplateCreateRequest request) {
    FileTemplateConfigUpsertParam param = new FileTemplateConfigUpsertParam();
    param.setTenantId(tenantId);
    param.setTemplateCode(request.getTemplateCode());
    param.setBasicInfo(
        basicInfo(
            request.getTemplateName(),
            request.getTemplateType(),
            request.getBizType(),
            request.getEnabled() != null ? request.getEnabled() : true,
            version,
            request.getDescription()));
    FileTemplateConfigUpsertParam.FormatOptions format =
        new FileTemplateConfigUpsertParam.FormatOptions();
    format.setFileFormatType(request.getFileFormatType());
    format.setCharset(request.getCharset());
    format.setTargetCharset(request.getTargetCharset());
    // 以下字段 DB NOT NULL 但允许 DEFAULT;BE create 显式塞 null 会触发 not-null violation,
    // 这里补与 V6 迁移一致的默认值(BE-ISSUE-4)。
    format.setWithBom(request.getWithBom() != null ? request.getWithBom() : Boolean.FALSE);
    format.setLineSeparator(request.getLineSeparator());
    format.setDelimiter(request.getDelimiter());
    format.setQuoteChar(request.getQuoteChar());
    format.setEscapeChar(request.getEscapeChar());
    format.setRecordLength(request.getRecordLength() != null ? request.getRecordLength() : 0);
    format.setHeaderRows(request.getHeaderRows() != null ? request.getHeaderRows() : 0);
    format.setFooterRows(request.getFooterRows() != null ? request.getFooterRows() : 0);
    format.setHeaderTemplateJson(request.getHeaderTemplateJson());
    format.setTrailerTemplateJson(request.getTrailerTemplateJson());
    format.setChecksumType(request.getChecksumType() != null ? request.getChecksumType() : "NONE");
    format.setCompressType(request.getCompressType() != null ? request.getCompressType() : "NONE");
    format.setEncryptType(request.getEncryptType() != null ? request.getEncryptType() : "NONE");
    format.setNamingRule(request.getNamingRule());
    format.setFieldMappingsJson(request.getFieldMappingsJson());
    format.setValidationRuleSetJson(request.getValidationRuleSetJson());
    param.setFormat(format);
    param.setQuery(
        queryOptions(
            request.getDefaultQueryCode(),
            request.getDefaultQuerySql(),
            request.getQueryParamSchemaJson()));
    // Runtime: DB 默认 streaming=true / page=1000 / fetch=1000 / chunk=500,补一致默认
    param.setRuntime(
        runtimeOptions(
            request.getStreamingEnabled() != null ? request.getStreamingEnabled() : Boolean.TRUE,
            request.getPageSize() != null ? request.getPageSize() : 1000,
            request.getFetchSize() != null ? request.getFetchSize() : 1000,
            request.getChunkSize() != null ? request.getChunkSize() : 500));
    // Security: DB 全部 NOT NULL DEFAULT FALSE,补默认避免 not-null violation
    SecurityOptionsInput securityInput =
        SecurityOptionsInput.builder()
            .previewMaskingEnabled(coalesceFalse(request.getPreviewMaskingEnabled()))
            .errorLineMaskingEnabled(coalesceFalse(request.getErrorLineMaskingEnabled()))
            .logMaskingEnabled(coalesceFalse(request.getLogMaskingEnabled()))
            .contentEncryptionEnabled(coalesceFalse(request.getContentEncryptionEnabled()))
            .encryptionKeyRef(request.getEncryptionKeyRef())
            .downloadRequiresApproval(coalesceFalse(request.getDownloadRequiresApproval()))
            .maskingRuleSet(request.getMaskingRuleSet())
            .build();
    param.setSecurity(securityOptions(securityInput));
    param.setPluginRefs(pluginRefs(request.getLoadTargetRef(), request.getExportDataRef()));
    param.setAudit(auditOptions(operator, operator));
    return param;
  }

  private FileTemplateConfigUpsertParam buildUpdateParam(
      String tenantId,
      String templateCode,
      int version,
      String operator,
      FileTemplateUpdateRequest request,
      Map<String, Object> existing) {
    FileTemplateConfigUpsertParam param = new FileTemplateConfigUpsertParam();
    param.setTenantId(tenantId);
    param.setTemplateCode(templateCode);
    param.setBasicInfo(buildBasicInfoForUpdate(request, existing, version));
    param.setFormat(buildFormatForUpdate(request, existing));
    param.setQuery(buildQueryForUpdate(request, existing));
    param.setRuntime(buildRuntimeForUpdate(request, existing));
    param.setSecurity(securityOptions(buildSecurityInputForUpdate(request, existing)));
    param.setPluginRefs(buildPluginRefsForUpdate(request, existing));
    param.setAudit(auditOptions(operator, operator));
    return param;
  }

  private FileTemplateConfigUpsertParam.BasicInfo buildBasicInfoForUpdate(
      FileTemplateUpdateRequest req, Map<String, Object> existing, int version) {
    return basicInfo(
        coalesceString(req.getTemplateName(), existing, "template_name"),
        coalesceString(req.getTemplateType(), existing, "template_type"),
        coalesceString(req.getBizType(), existing, "biz_type"),
        coalesceBoolean(req.getEnabled(), existing, "enabled"),
        version,
        coalesceString(req.getDescription(), existing, "description"));
  }

  private FileTemplateConfigUpsertParam.FormatOptions buildFormatForUpdate(
      FileTemplateUpdateRequest req, Map<String, Object> existing) {
    FileTemplateConfigUpsertParam.FormatOptions format =
        new FileTemplateConfigUpsertParam.FormatOptions();
    format.setFileFormatType(coalesceString(req.getFileFormatType(), existing, "file_format_type"));
    format.setCharset(coalesceString(req.getCharset(), existing, "charset"));
    format.setTargetCharset(coalesceString(req.getTargetCharset(), existing, "target_charset"));
    format.setWithBom(coalesceBoolean(req.getWithBom(), existing, "with_bom"));
    format.setLineSeparator(coalesceString(req.getLineSeparator(), existing, "line_separator"));
    format.setDelimiter(coalesceString(req.getDelimiter(), existing, "delimiter"));
    format.setQuoteChar(coalesceString(req.getQuoteChar(), existing, "quote_char"));
    format.setEscapeChar(coalesceString(req.getEscapeChar(), existing, "escape_char"));
    format.setRecordLength(coalesceInt(req.getRecordLength(), existing, "record_length"));
    format.setHeaderRows(coalesceInt(req.getHeaderRows(), existing, "header_rows"));
    format.setFooterRows(coalesceInt(req.getFooterRows(), existing, "footer_rows"));
    format.setHeaderTemplateJson(
        req.getHeaderTemplateJson() != null
            ? req.getHeaderTemplateJson()
            : stringValue(existing.get("header_template")));
    format.setTrailerTemplateJson(
        req.getTrailerTemplateJson() != null
            ? req.getTrailerTemplateJson()
            : stringValue(existing.get("trailer_template")));
    format.setChecksumType(coalesceString(req.getChecksumType(), existing, "checksum_type"));
    format.setCompressType(coalesceString(req.getCompressType(), existing, "compress_type"));
    format.setEncryptType(coalesceString(req.getEncryptType(), existing, "encrypt_type"));
    format.setNamingRule(coalesceString(req.getNamingRule(), existing, "naming_rule"));
    format.setFieldMappingsJson(
        req.getFieldMappingsJson() != null
            ? req.getFieldMappingsJson()
            : stringValue(existing.get("field_mappings")));
    format.setValidationRuleSetJson(
        req.getValidationRuleSetJson() != null
            ? req.getValidationRuleSetJson()
            : stringValue(existing.get("validation_rule_set")));
    return format;
  }

  private FileTemplateConfigUpsertParam.QueryOptions buildQueryForUpdate(
      FileTemplateUpdateRequest req, Map<String, Object> existing) {
    return queryOptions(
        coalesceString(req.getDefaultQueryCode(), existing, "default_query_code"),
        coalesceString(req.getDefaultQuerySql(), existing, "default_query_sql"),
        req.getQueryParamSchemaJson() != null
            ? req.getQueryParamSchemaJson()
            : stringValue(existing.get("query_param_schema")));
  }

  private FileTemplateConfigUpsertParam.RuntimeOptions buildRuntimeForUpdate(
      FileTemplateUpdateRequest req, Map<String, Object> existing) {
    return runtimeOptions(
        coalesceBoolean(req.getStreamingEnabled(), existing, "streaming_enabled"),
        coalesceInt(req.getPageSize(), existing, "page_size"),
        coalesceInt(req.getFetchSize(), existing, "fetch_size"),
        coalesceInt(req.getChunkSize(), existing, "chunk_size"));
  }

  private SecurityOptionsInput buildSecurityInputForUpdate(
      FileTemplateUpdateRequest req, Map<String, Object> existing) {
    SecurityOptionsInput securityInput =
        SecurityOptionsInput.builder()
            .previewMaskingEnabled(
                coalesceBoolean(
                    req.getPreviewMaskingEnabled(), existing, "preview_masking_enabled"))
            .errorLineMaskingEnabled(
                coalesceBoolean(
                    req.getErrorLineMaskingEnabled(), existing, "error_line_masking_enabled"))
            .logMaskingEnabled(
                coalesceBoolean(req.getLogMaskingEnabled(), existing, "log_masking_enabled"))
            .contentEncryptionEnabled(
                coalesceBoolean(
                    req.getContentEncryptionEnabled(), existing, "content_encryption_enabled"))
            .encryptionKeyRef(
                coalesceString(req.getEncryptionKeyRef(), existing, "encryption_key_ref"))
            .downloadRequiresApproval(
                coalesceBoolean(
                    req.getDownloadRequiresApproval(), existing, "download_requires_approval"))
            .maskingRuleSet(coalesceString(req.getMaskingRuleSet(), existing, "masking_rule_set"))
            .build();
    return securityInput;
  }

  private FileTemplateConfigUpsertParam.PluginRefs buildPluginRefsForUpdate(
      FileTemplateUpdateRequest req, Map<String, Object> existing) {
    return pluginRefs(
        coalesceString(req.getLoadTargetRef(), existing, "load_target_ref"),
        coalesceString(req.getExportDataRef(), existing, "export_data_ref"));
  }

  private FileTemplateConfigUpsertParam.PluginRefs pluginRefs(
      String loadTargetRef, String exportDataRef) {
    FileTemplateConfigUpsertParam.PluginRefs pluginRefs =
        new FileTemplateConfigUpsertParam.PluginRefs();
    pluginRefs.setLoadTargetRef(blankToNull(loadTargetRef));
    pluginRefs.setExportDataRef(blankToNull(exportDataRef));
    return pluginRefs;
  }

  private FileTemplateMappingDraftResult draftImportMapping(
      FileTemplateMappingDraftCommand command) {
    List<String> warnings = new ArrayList<>();
    List<Map<String, Object>> fieldMappings = new ArrayList<>();
    List<Map<String, Object>> columnMappings = new ArrayList<>();
    for (FileTemplateMappingDraftCommand.Field field : fieldsOf(command)) {
      if (field == null) {
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
    if (!columnMappings.isEmpty()) {
      jdbcMappedImport.put("columnMappings", columnMappings);
    }
    if (command.conflictColumns() != null && !command.conflictColumns().isEmpty()) {
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
    return new FileTemplateMappingDraftResult(
        "IMPORT",
        writeJson(fieldMappings),
        writeJson(queryParamSchema),
        blankToNull(command.defaultQuerySql()),
        warnings);
  }

  private FileTemplateMappingDraftResult draftExportMapping(
      FileTemplateMappingDraftCommand command) {
    List<String> warnings = new ArrayList<>();
    List<Map<String, Object>> fieldMappings = new ArrayList<>();
    List<String> selectedColumns = new ArrayList<>();
    for (FileTemplateMappingDraftCommand.Field field : fieldsOf(command)) {
      if (field == null) {
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
      selectedColumns.add(sourceColumn);
    }

    Map<String, Object> sqlTemplateExport = new LinkedHashMap<>();
    putIfText(sqlTemplateExport, "schema", command.schemaName());
    putIfText(sqlTemplateExport, "table", command.tableName());
    if (!selectedColumns.isEmpty()) {
      sqlTemplateExport.put("columns", selectedColumns);
    }
    sqlTemplateExport.put("cursorColumn", "id");
    Map<String, Object> queryParamSchema = new LinkedHashMap<>();
    queryParamSchema.put("export_data_ref", WorkerPluginIds.EXPORT_DATA_SQL_TEMPLATE);
    queryParamSchema.put("sqlTemplateExport", sqlTemplateExport);

    String defaultQuerySql = blankToNull(command.defaultQuerySql());
    if (defaultQuerySql == null && Texts.hasText(command.tableName())) {
      if (canGenerateDefaultExportSql(command, selectedColumns)) {
        defaultQuerySql = defaultExportSql(command, selectedColumns);
      } else {
        warnings.add("schemaName/tableName/tenantColumn/sourceColumn 存在非 SQL 标识符,已跳过默认 SQL 生成");
      }
    }
    if (defaultQuerySql == null) {
      warnings.add("未填写 defaultQuerySql/tableName,保存前需要补齐导出 SQL");
    }
    return new FileTemplateMappingDraftResult(
        "EXPORT", writeJson(fieldMappings), writeJson(queryParamSchema), defaultQuerySql, warnings);
  }

  private String defaultExportSql(FileTemplateMappingDraftCommand command, List<String> columns) {
    String selectColumns = columns.isEmpty() ? "id" : String.join(", ", columns);
    String tableRef =
        Texts.hasText(command.schemaName())
            ? command.schemaName() + "." + command.tableName()
            : command.tableName();
    String tenantColumn = firstText(command.tenantColumn(), "tenant_id");
    return "select "
        + selectColumns
        + " from "
        + tableRef
        + " where "
        + tenantColumn
        + " = :tenantId";
  }

  private boolean canGenerateDefaultExportSql(
      FileTemplateMappingDraftCommand command, List<String> columns) {
    if (Texts.hasText(command.schemaName()) && !isSqlIdentifier(command.schemaName())) {
      return false;
    }
    if (!isSqlIdentifier(command.tableName())) {
      return false;
    }
    if (!isSqlIdentifier(firstText(command.tenantColumn(), "tenant_id"))) {
      return false;
    }
    return columns.stream().allMatch(DefaultConsoleFileTemplateApplicationService::isSqlIdentifier);
  }

  private static boolean isSqlIdentifier(String value) {
    return Texts.hasText(value) && SQL_IDENTIFIER.matcher(value.trim()).matches();
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw BizException.of(
          ResultCode.SYSTEM_ERROR,
          "error.common.system_error_detail",
          "file template json draft failed");
    }
  }

  private String normalizeDirection(String direction) {
    if (!Texts.hasText(direction)) {
      return "IMPORT";
    }
    String normalized = direction.trim().toUpperCase(Locale.ROOT);
    Guard.require(
        "IMPORT".equals(normalized) || "EXPORT".equals(normalized),
        "direction must be IMPORT or EXPORT");
    return normalized;
  }

  private static List<FileTemplateMappingDraftCommand.Field> fieldsOf(
      FileTemplateMappingDraftCommand command) {
    return command.fields() == null ? List.of() : command.fields();
  }

  private static void putIfText(Map<String, Object> map, String key, String value) {
    if (Texts.hasText(value)) {
      map.put(key, value.trim());
    }
  }

  private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  private static String firstText(String preferred, String fallback) {
    return Texts.hasText(preferred) ? preferred.trim() : blankToNull(fallback);
  }

  private static String blankToNull(String value) {
    return Texts.hasText(value) ? value.trim() : null;
  }

  private static Boolean coalesceFalse(Boolean v) {
    return v != null ? v : Boolean.FALSE;
  }

  private static String coalesceString(String preferred, Map<String, Object> existing, String key) {
    return preferred != null ? preferred : (String) existing.get(key);
  }

  private static Boolean coalesceBoolean(
      Boolean preferred, Map<String, Object> existing, String key) {
    return preferred != null ? preferred : (Boolean) existing.get(key);
  }

  private Integer coalesceInt(Integer preferred, Map<String, Object> existing, String key) {
    return preferred != null ? preferred : intValue(existing.get(key));
  }

  private FileTemplateConfigUpsertParam.BasicInfo basicInfo(
      String templateName,
      String templateType,
      String bizType,
      Boolean enabled,
      Integer version,
      String description) {
    FileTemplateConfigUpsertParam.BasicInfo basicInfo =
        new FileTemplateConfigUpsertParam.BasicInfo();
    basicInfo.setTemplateName(templateName);
    basicInfo.setTemplateType(templateType);
    basicInfo.setBizType(bizType);
    basicInfo.setEnabled(enabled);
    basicInfo.setVersion(version);
    basicInfo.setDescription(description);
    return basicInfo;
  }

  private FileTemplateConfigUpsertParam.QueryOptions queryOptions(
      String defaultQueryCode, String defaultQuerySql, String queryParamSchemaJson) {
    FileTemplateConfigUpsertParam.QueryOptions query =
        new FileTemplateConfigUpsertParam.QueryOptions();
    query.setDefaultQueryCode(defaultQueryCode);
    query.setDefaultQuerySql(defaultQuerySql);
    query.setQueryParamSchemaJson(queryParamSchemaJson);
    return query;
  }

  private FileTemplateConfigUpsertParam.RuntimeOptions runtimeOptions(
      Boolean streamingEnabled, Integer pageSize, Integer fetchSize, Integer chunkSize) {
    FileTemplateConfigUpsertParam.RuntimeOptions runtime =
        new FileTemplateConfigUpsertParam.RuntimeOptions();
    runtime.setStreamingEnabled(streamingEnabled);
    runtime.setPageSize(pageSize);
    runtime.setFetchSize(fetchSize);
    runtime.setChunkSize(chunkSize);
    return runtime;
  }

  @Builder
  private record SecurityOptionsInput(
      Boolean previewMaskingEnabled,
      Boolean errorLineMaskingEnabled,
      Boolean logMaskingEnabled,
      Boolean contentEncryptionEnabled,
      String encryptionKeyRef,
      Boolean downloadRequiresApproval,
      String maskingRuleSet) {}

  private FileTemplateConfigUpsertParam.SecurityOptions securityOptions(
      SecurityOptionsInput input) {
    FileTemplateConfigUpsertParam.SecurityOptions security =
        new FileTemplateConfigUpsertParam.SecurityOptions();
    security.setPreviewMaskingEnabled(input.previewMaskingEnabled());
    security.setErrorLineMaskingEnabled(input.errorLineMaskingEnabled());
    security.setLogMaskingEnabled(input.logMaskingEnabled());
    security.setContentEncryptionEnabled(input.contentEncryptionEnabled());
    security.setEncryptionKeyRef(input.encryptionKeyRef());
    security.setDownloadRequiresApproval(input.downloadRequiresApproval());
    security.setMaskingRuleSet(input.maskingRuleSet());
    return security;
  }

  private FileTemplateConfigUpsertParam.AuditOptions auditOptions(
      String createdBy, String updatedBy) {
    FileTemplateConfigUpsertParam.AuditOptions audit =
        new FileTemplateConfigUpsertParam.AuditOptions();
    audit.setCreatedBy(createdBy);
    audit.setUpdatedBy(updatedBy);
    return audit;
  }

  private Integer intValue(Object value) {
    return value instanceof Number number ? number.intValue() : null;
  }

  private String stringValue(Object value) {
    return value != null ? value.toString() : null;
  }
}
