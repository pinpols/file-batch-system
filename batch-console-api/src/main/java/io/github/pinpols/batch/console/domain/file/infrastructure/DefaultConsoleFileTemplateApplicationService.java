package io.github.pinpols.batch.console.domain.file.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.file.ExportFileNameResolver;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.plugin.WorkerPluginIds;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.domain.file.application.ConsoleFileTemplateApplicationService;
import io.github.pinpols.batch.console.domain.file.application.FileTemplateMappingDraftCommand;
import io.github.pinpols.batch.console.domain.file.application.FileTemplateMappingDraftResult;
import io.github.pinpols.batch.console.domain.file.application.contract.query.FileTemplateQueryRequest;
import io.github.pinpols.batch.console.domain.file.application.contract.request.FileNamePreviewRequest;
import io.github.pinpols.batch.console.domain.file.application.contract.request.FileTemplateCreateRequest;
import io.github.pinpols.batch.console.domain.file.application.contract.request.FileTemplateUpdateRequest;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFileProjectionMapper;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFileTemplateResponse;
import io.github.pinpols.batch.console.domain.file.application.contract.response.FileNamePreviewResponse;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.file.param.FileTemplateConfigUpsertParam;
import io.github.pinpols.batch.console.domain.file.param.FileTemplateEnableFence;
import io.github.pinpols.batch.console.domain.file.query.FileTemplateConfigQuery;
import io.github.pinpols.batch.console.domain.file.support.FixedWidthMappingValidation;
import io.github.pinpols.batch.console.domain.file.view.FileTemplateConfigQueryView;
import io.github.pinpols.batch.console.domain.job.infrastructure.DefaultConsoleJobDefinitionApplicationService;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 文件模板配置的 CRUD：list / get / create / update / toggle。
 *
 * <p>唯一键：{@code (tenantId, templateCode, version)}——允许同 code 多版本并存，用于灰度发布或滚动升级。 create 时若同 (code,
 * version) 已存在抛 {@code CONFLICT}；update 固定沿用现有行的 version，<b>忽略</b>入参携带的 version 覆盖—— 否则 upsert 的
 * {@code on conflict (tenant_id, template_code, version)} 不命中 path id 指向的现有行， 会 INSERT 一条新 version
 * 幽灵行(新 id)，而回读仍按旧 id 取回陈旧行。新版号应走 create 而非 update。
 *
 * <p>与 {@link DefaultConsoleJobDefinitionApplicationService} 不同，本类不调 cache invalidation—— 文件模板不在
 * orchestrator launch 热路径上读取（由 worker 导入/导出阶段按需拉取），无需前置失效 Redis 缓存。
 */
@Service
@RequiredArgsConstructor
@SuppressWarnings("java:S2259")
public class DefaultConsoleFileTemplateApplicationService
    implements ConsoleFileTemplateApplicationService {

  private final FileTemplateConfigMapper mapper;
  private final TenantIdResolver tenantGuard;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final ObjectMapper objectMapper;

  @Override
  public PageResponse<ConsoleFileTemplateResponse> list(FileTemplateQueryRequest request) {
    String tenantId = resolveTenant(request.getTenantId());
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    FileTemplateConfigQuery query = new FileTemplateConfigQuery(
        tenantId,
        request.getKeyword(),
        request.getTemplateCode(),
        request.getTemplateName(),
        request.getTemplateType(),
        request.getBizType(),
        request.getEnabled(),
        pageRequest);
    long total = mapper.countByQuery(query);
    List<FileTemplateConfigQueryView> items = mapper.selectByQuery(query);
    return new PageResponse<>(
        total,
        pageRequest.pageNo(),
        pageRequest.pageSize(),
        items.stream().map(ConsoleFileProjectionMapper::templateView).toList());
  }

  @Override
  public ConsoleFileTemplateResponse get(Long id, String tenantId) {
    String resolved = resolveTenant(tenantId);
    FileTemplateConfigQueryView templateRow = mapper.selectById(resolved, id);
    FileTemplateConfigQueryView requiredTemplateRow =
        Guard.requireFound(templateRow, "file template not found: " + id);
    return ConsoleFileProjectionMapper.templateView(requiredTemplateRow);
  }

  @Override
  public ConsoleFileTemplateResponse create(FileTemplateCreateRequest request) {
    String tenantId = resolveTenant(request.getTenantId());
    int version = request.getVersion() != null ? request.getVersion() : 1;
    FileTemplateConfigQueryView existing =
        mapper.selectByUniqueKey(tenantId, request.getTemplateCode(), version);
    if (existing != null) {
      throw BizException.of(
          ResultCode.CONFLICT,
          ResultCode.CONFLICT.detailKey(),
          "template code + version already exists: " + request.getTemplateCode() + "/" + version);
    }
    String operator = requestMetadataResolver.current().operatorId();
    FileTemplateConfigUpsertParam param = buildCreateParam(tenantId, version, operator, request);
    validateFixedWidthMappings(param.getBasicInfo(), param.getFormat());
    mapper.upsertFileTemplateConfig(param);
    String templateCode = request.getTemplateCode();
    FileTemplateConfigQueryView createdTemplateRow =
        mapper.selectByUniqueKey(tenantId, templateCode, version);
    return ConsoleFileProjectionMapper.templateView(createdTemplateRow);
  }

  @Override
  public ConsoleFileTemplateResponse update(Long id, FileTemplateUpdateRequest request) {
    String tenantId = resolveTenant(request.getTenantId());
    FileTemplateConfigQueryView existing =
        Guard.requireFound(mapper.selectById(tenantId, id), "file template not found: " + id);
    String operator = requestMetadataResolver.current().operatorId();
    String templateCode = existing.templateCode();
    // #9 (adv-review 2026-07-13)：update 不得改 version —— upsert 的 on conflict 键含 version，
    // 入参携带新 version 时不命中 path id 指向的现有行，反而 INSERT 一条新 version 幽灵行(新 id)，
    // 而回读仍按旧 id 取回陈旧行。update 语义固定就地作用于现有行，忽略入参的 version 覆盖。
    int version = EmptyChecks.isNotNull(existing.version()) ? existing.version() : 1;
    FileTemplateConfigUpsertParam param =
        buildUpdateParam(tenantId, templateCode, version, operator, request, existing);
    validateFixedWidthMappings(param.getBasicInfo(), param.getFormat());
    mapper.upsertFileTemplateConfig(param);
    FileTemplateConfigQueryView updatedTemplateRow = mapper.selectById(tenantId, id);
    return ConsoleFileProjectionMapper.templateView(updatedTemplateRow);
  }

  @Override
  public void toggle(Long id, String tenantId, Boolean enabled) {
    String resolved = resolveTenant(tenantId);
    if (Boolean.TRUE.equals(enabled)) {
      FileTemplateConfigQueryView existing =
          Guard.requireFound(mapper.selectById(resolved, id), "file template not found: " + id);
      FixedWidthMappingValidation.validate(
          objectMapper,
          true,
          existing.templateType(),
          existing.fileFormatType(),
          stringValue(existing.fieldMappings()));
      FileTemplateEnableFence fence = new FileTemplateEnableFence(
          resolved,
          id,
          existing.templateType(),
          existing.fileFormatType(),
          stringValue(existing.fieldMappings()));
      if (mapper.toggleEnabledIfUnchanged(fence) == 0) {
        throw BizException.of(
            ResultCode.STATE_CONFLICT,
            ResultCode.STATE_CONFLICT.detailKey(),
            "file template changed while enabling: " + id);
      }
      return;
    }
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

  @Override
  public FileNamePreviewResponse previewFileName(FileNamePreviewRequest request) {
    String tenantId = resolveTenant(request.getTenantId());
    ExportFileNameResolver.Input input = ExportFileNameResolver.Input.builder()
        .namingRule(request.getNamingRule())
        .fileFormatType(request.getFileFormatType())
        .bizType(request.getBizType())
        .bizDate(request.getBizDate())
        .tenantId(tenantId)
        .batchNo(request.getBatchNo())
        .region(request.getRegion())
        .version(request.getVersion())
        .build();
    String resolvedFileName = ExportFileNameResolver.resolve(input);
    return new FileNamePreviewResponse(resolvedFileName);
  }

  private String resolveTenant(String tenantId) {
    return tenantGuard.resolveTenant(tenantId);
  }

  private void validateFixedWidthMappings(
      FileTemplateConfigUpsertParam.BasicInfo basicInfo,
      FileTemplateConfigUpsertParam.FormatOptions format) {
    FixedWidthMappingValidation.validate(
        objectMapper,
        basicInfo.getEnabled(),
        basicInfo.getTemplateType(),
        format.getFileFormatType(),
        format.getFieldMappingsJson());
  }

  private FileTemplateConfigUpsertParam buildCreateParam(
      String tenantId, int version, String operator, FileTemplateCreateRequest request) {
    FileTemplateConfigUpsertParam param = new FileTemplateConfigUpsertParam();
    param.setTenantId(tenantId);
    param.setTemplateCode(request.getTemplateCode());
    param.setBasicInfo(basicInfo(
        request.getTemplateName(),
        request.getTemplateType(),
        request.getBizType(),
        request.getEnabled() == null || request.getEnabled(),
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
    param.setQuery(queryOptions(
        request.getDefaultQueryCode(),
        request.getDefaultQuerySql(),
        request.getQueryParamSchemaJson()));
    // Runtime: DB 默认 streaming=true / page=1000 / fetch=1000 / chunk=500,补一致默认
    Boolean effectiveStreamingEnabled = EmptyChecks.isNotNull(request.getStreamingEnabled())
        ? request.getStreamingEnabled()
        : Boolean.TRUE;
    Integer effectivePageSize =
        EmptyChecks.isNotNull(request.getPageSize()) ? request.getPageSize() : 1000;
    Integer effectiveFetchSize =
        EmptyChecks.isNotNull(request.getFetchSize()) ? request.getFetchSize() : 1000;
    Integer effectiveChunkSize =
        EmptyChecks.isNotNull(request.getChunkSize()) ? request.getChunkSize() : 500;
    FileTemplateConfigUpsertParam.RuntimeOptions runtimeOptions = runtimeOptions(
        effectiveStreamingEnabled, effectivePageSize, effectiveFetchSize, effectiveChunkSize);
    param.setRuntime(runtimeOptions);
    // Security: DB 全部 NOT NULL DEFAULT FALSE,补默认避免 not-null violation
    SecurityOptionsInput securityInput = SecurityOptionsInput.builder()
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
      FileTemplateConfigQueryView existing) {
    FileTemplateConfigUpsertParam param = new FileTemplateConfigUpsertParam();
    param.setTenantId(tenantId);
    param.setTemplateCode(templateCode);
    FileTemplateConfigUpsertParam.BasicInfo basicInfo =
        buildBasicInfoForUpdate(request, existing, version);
    param.setBasicInfo(basicInfo);
    FileTemplateConfigUpsertParam.FormatOptions formatOptions =
        buildFormatForUpdate(request, existing);
    param.setFormat(formatOptions);
    FileTemplateConfigUpsertParam.QueryOptions queryOptions =
        buildQueryForUpdate(request, existing);
    param.setQuery(queryOptions);
    FileTemplateConfigUpsertParam.RuntimeOptions runtimeOptions =
        buildRuntimeForUpdate(request, existing);
    param.setRuntime(runtimeOptions);
    DefaultConsoleFileTemplateApplicationService.SecurityOptionsInput securityOptionsInput =
        buildSecurityInputForUpdate(request, existing);
    FileTemplateConfigUpsertParam.SecurityOptions securityOptions =
        securityOptions(securityOptionsInput);
    param.setSecurity(securityOptions);
    FileTemplateConfigUpsertParam.PluginRefs pluginRefs =
        buildPluginRefsForUpdate(request, existing);
    param.setPluginRefs(pluginRefs);
    param.setAudit(auditOptions(operator, operator));
    return param;
  }

  private FileTemplateConfigUpsertParam.BasicInfo buildBasicInfoForUpdate(
      FileTemplateUpdateRequest req, FileTemplateConfigQueryView existing, int version) {
    String templateName = coalesceString(req.getTemplateName(), existing.templateName());
    String templateType = coalesceString(req.getTemplateType(), existing.templateType());
    String bizType = coalesceString(req.getBizType(), existing.bizType());
    Boolean enabled = coalesceBoolean(req.getEnabled(), existing.enabled());
    String description = coalesceString(req.getDescription(), existing.description());
    return basicInfo(templateName, templateType, bizType, enabled, version, description);
  }

  private FileTemplateConfigUpsertParam.FormatOptions buildFormatForUpdate(
      FileTemplateUpdateRequest req, FileTemplateConfigQueryView existing) {
    FileTemplateConfigUpsertParam.FormatOptions format =
        new FileTemplateConfigUpsertParam.FormatOptions();
    format.setFileFormatType(coalesceString(req.getFileFormatType(), existing.fileFormatType()));
    format.setCharset(coalesceString(req.getCharset(), existing.charset()));
    format.setTargetCharset(coalesceString(req.getTargetCharset(), existing.targetCharset()));
    format.setWithBom(coalesceBoolean(req.getWithBom(), existing.withBom()));
    format.setLineSeparator(coalesceString(req.getLineSeparator(), existing.lineSeparator()));
    format.setDelimiter(coalesceString(req.getDelimiter(), existing.delimiter()));
    format.setQuoteChar(coalesceString(req.getQuoteChar(), existing.quoteChar()));
    format.setEscapeChar(coalesceString(req.getEscapeChar(), existing.escapeChar()));
    format.setRecordLength(coalesceInt(req.getRecordLength(), existing.recordLength()));
    format.setHeaderRows(coalesceInt(req.getHeaderRows(), existing.headerRows()));
    format.setFooterRows(coalesceInt(req.getFooterRows(), existing.footerRows()));
    format.setHeaderTemplateJson(
        req.getHeaderTemplateJson() != null
            ? req.getHeaderTemplateJson()
            : stringValue(existing.headerTemplate()));
    format.setTrailerTemplateJson(
        req.getTrailerTemplateJson() != null
            ? req.getTrailerTemplateJson()
            : stringValue(existing.trailerTemplate()));
    format.setChecksumType(coalesceString(req.getChecksumType(), existing.checksumType()));
    format.setCompressType(coalesceString(req.getCompressType(), existing.compressType()));
    format.setEncryptType(coalesceString(req.getEncryptType(), existing.encryptType()));
    format.setNamingRule(coalesceString(req.getNamingRule(), existing.namingRule()));
    format.setFieldMappingsJson(
        req.getFieldMappingsJson() != null
            ? req.getFieldMappingsJson()
            : stringValue(existing.fieldMappings()));
    format.setValidationRuleSetJson(
        req.getValidationRuleSetJson() != null
            ? req.getValidationRuleSetJson()
            : stringValue(existing.validationRuleSet()));
    return format;
  }

  private FileTemplateConfigUpsertParam.QueryOptions buildQueryForUpdate(
      FileTemplateUpdateRequest req, FileTemplateConfigQueryView existing) {
    String defaultQueryCode =
        coalesceString(req.getDefaultQueryCode(), existing.defaultQueryCode());
    String defaultQuerySql = coalesceString(req.getDefaultQuerySql(), existing.defaultQuerySql());
    String queryParamSchema = EmptyChecks.isNotNull(req.getQueryParamSchemaJson())
        ? req.getQueryParamSchemaJson()
        : stringValue(existing.queryParamSchema());
    return queryOptions(defaultQueryCode, defaultQuerySql, queryParamSchema);
  }

  private FileTemplateConfigUpsertParam.RuntimeOptions buildRuntimeForUpdate(
      FileTemplateUpdateRequest req, FileTemplateConfigQueryView existing) {
    Boolean streamingEnabled =
        coalesceBoolean(req.getStreamingEnabled(), existing.streamingEnabled());
    Integer pageSize = coalesceInt(req.getPageSize(), existing.pageSize());
    Integer fetchSize = coalesceInt(req.getFetchSize(), existing.fetchSize());
    Integer chunkSize = coalesceInt(req.getChunkSize(), existing.chunkSize());
    return runtimeOptions(streamingEnabled, pageSize, fetchSize, chunkSize);
  }

  private SecurityOptionsInput buildSecurityInputForUpdate(
      FileTemplateUpdateRequest req, FileTemplateConfigQueryView existing) {
    SecurityOptionsInput.SecurityOptionsInputBuilder securityOptionsBuilder =
        SecurityOptionsInput.builder();
    Boolean previewMaskingEnabled =
        coalesceBoolean(req.getPreviewMaskingEnabled(), existing.previewMaskingEnabled());
    securityOptionsBuilder.previewMaskingEnabled(previewMaskingEnabled);
    Boolean errorLineMaskingEnabled =
        coalesceBoolean(req.getErrorLineMaskingEnabled(), existing.errorLineMaskingEnabled());
    securityOptionsBuilder.errorLineMaskingEnabled(errorLineMaskingEnabled);
    Boolean logMaskingEnabled =
        coalesceBoolean(req.getLogMaskingEnabled(), existing.logMaskingEnabled());
    securityOptionsBuilder.logMaskingEnabled(logMaskingEnabled);
    Boolean contentEncryptionEnabled =
        coalesceBoolean(req.getContentEncryptionEnabled(), existing.contentEncryptionEnabled());
    securityOptionsBuilder.contentEncryptionEnabled(contentEncryptionEnabled);
    String encryptionKeyRef =
        coalesceString(req.getEncryptionKeyRef(), existing.encryptionKeyRef());
    securityOptionsBuilder.encryptionKeyRef(encryptionKeyRef);
    Boolean downloadRequiresApproval =
        coalesceBoolean(req.getDownloadRequiresApproval(), existing.downloadRequiresApproval());
    securityOptionsBuilder.downloadRequiresApproval(downloadRequiresApproval);
    String maskingRuleSet = coalesceString(req.getMaskingRuleSet(), existing.maskingRuleSet());
    securityOptionsBuilder.maskingRuleSet(maskingRuleSet);
    return securityOptionsBuilder.build();
  }

  private FileTemplateConfigUpsertParam.PluginRefs buildPluginRefsForUpdate(
      FileTemplateUpdateRequest req, FileTemplateConfigQueryView existing) {
    String loadTargetRef = coalesceString(req.getLoadTargetRef(), existing.loadTargetRef());
    String exportDataRef = coalesceString(req.getExportDataRef(), existing.exportDataRef());
    return pluginRefs(loadTargetRef, exportDataRef);
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
    String fieldMappingsJson = writeJson(fieldMappings);
    String queryParamSchemaJson = writeJson(queryParamSchema);
    String defaultQuerySql = blankToNull(command.defaultQuerySql());
    return new FileTemplateMappingDraftResult(
        "IMPORT", fieldMappingsJson, queryParamSchemaJson, defaultQuerySql, warnings);
  }

  private FileTemplateMappingDraftResult draftExportMapping(
      FileTemplateMappingDraftCommand command) {
    List<String> warnings = new ArrayList<>();
    List<Map<String, Object>> fieldMappings = new ArrayList<>();
    List<Map<String, Object>> csvColumns = new ArrayList<>();
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
      Map<String, Object> csvColumn = new LinkedHashMap<>();
      csvColumn.put("header", firstText(field.header(), sourceColumn));
      csvColumn.put("source", "detail." + sourceColumn);
      csvColumns.add(csvColumn);
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
    if (EmptyChecks.isNotEmpty(csvColumns)) {
      // Worker 的文件列来自 csvColumns；SQL 游标列只服务分页，不应自动进入文件。
      queryParamSchema.put("csvColumns", csvColumns);
    }

    String defaultQuerySql = blankToNull(command.defaultQuerySql());
    if (defaultQuerySql == null) {
      warnings.add("请填写实际业务导出 SQL，按 :tenantId 和 :batchNo 过滤租户及批次，并返回分页游标列");
    }
    String fieldMappingsJson = writeJson(fieldMappings);
    String queryParamSchemaJson = writeJson(queryParamSchema);
    return new FileTemplateMappingDraftResult(
        "EXPORT", fieldMappingsJson, queryParamSchemaJson, defaultQuerySql, warnings);
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw BizException.of(
          ResultCode.SYSTEM_ERROR,
          ResultCode.SYSTEM_ERROR.detailKey(),
          "file template json draft failed");
    }
  }

  private String normalizeDirection(String direction) {
    if (!Texts.hasText(direction)) {
      return "IMPORT";
    }
    String trimmedDirection = direction.trim();
    String normalized = trimmedDirection.toUpperCase(Locale.ROOT);
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

  private static String coalesceString(String preferred, String existing) {
    return EmptyChecks.isNotNull(preferred) ? preferred : existing;
  }

  private static Boolean coalesceBoolean(Boolean preferred, Boolean existing) {
    return EmptyChecks.isNotNull(preferred) ? preferred : existing;
  }

  private Integer coalesceInt(Integer preferred, Integer existing) {
    return EmptyChecks.isNotNull(preferred) ? preferred : existing;
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

  private String stringValue(Object value) {
    return value != null ? value.toString() : null;
  }
}
