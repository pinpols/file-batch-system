package com.example.batch.console.infrastructure.file;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.application.file.ConsoleFileTemplateApplicationService;
import com.example.batch.console.domain.param.FileTemplateConfigUpsertParam;
import com.example.batch.console.domain.query.FileTemplateConfigQuery;
import com.example.batch.console.infrastructure.job.DefaultConsoleJobDefinitionApplicationService;
import com.example.batch.console.mapper.FileTemplateConfigMapper;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.query.FileTemplateQueryRequest;
import com.example.batch.console.web.request.file.FileTemplateCreateRequest;
import com.example.batch.console.web.request.file.FileTemplateUpdateRequest;
import java.util.List;
import java.util.Map;
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

  private final FileTemplateConfigMapper mapper;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;

  @Override
  public PageResponse<Map<String, Object>> list(FileTemplateQueryRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
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
    String resolved = tenantGuard.resolveTenant(tenantId);
    return Guard.requireFound(mapper.selectById(resolved, id), "file template not found: " + id);
  }

  @Override
  public Map<String, Object> create(FileTemplateCreateRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
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
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
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
    String resolved = tenantGuard.resolveTenant(tenantId);
    int rows = mapper.toggleEnabled(resolved, id, enabled);
    if (rows == 0) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.file_template.not_found", id);
    }
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
    format.setWithBom(request.getWithBom());
    format.setLineSeparator(request.getLineSeparator());
    format.setDelimiter(request.getDelimiter());
    format.setQuoteChar(request.getQuoteChar());
    format.setEscapeChar(request.getEscapeChar());
    format.setRecordLength(request.getRecordLength());
    format.setHeaderRows(request.getHeaderRows());
    format.setFooterRows(request.getFooterRows());
    format.setHeaderTemplateJson(request.getHeaderTemplateJson());
    format.setTrailerTemplateJson(request.getTrailerTemplateJson());
    format.setChecksumType(request.getChecksumType());
    format.setCompressType(request.getCompressType());
    format.setEncryptType(request.getEncryptType());
    format.setNamingRule(request.getNamingRule());
    format.setFieldMappingsJson(request.getFieldMappingsJson());
    format.setValidationRuleSetJson(request.getValidationRuleSetJson());
    param.setFormat(format);
    param.setQuery(
        queryOptions(
            request.getDefaultQueryCode(),
            request.getDefaultQuerySql(),
            request.getQueryParamSchemaJson()));
    param.setRuntime(
        runtimeOptions(
            request.getStreamingEnabled(),
            request.getPageSize(),
            request.getFetchSize(),
            request.getChunkSize()));
    SecurityOptionsInput securityInput =
        SecurityOptionsInput.builder()
            .previewMaskingEnabled(request.getPreviewMaskingEnabled())
            .errorLineMaskingEnabled(request.getErrorLineMaskingEnabled())
            .logMaskingEnabled(request.getLogMaskingEnabled())
            .contentEncryptionEnabled(request.getContentEncryptionEnabled())
            .encryptionKeyRef(request.getEncryptionKeyRef())
            .downloadRequiresApproval(request.getDownloadRequiresApproval())
            .maskingRuleSet(request.getMaskingRuleSet())
            .build();
    param.setSecurity(securityOptions(securityInput));
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
    param.setBasicInfo(
        basicInfo(
            request.getTemplateName() != null
                ? request.getTemplateName()
                : (String) existing.get("template_name"),
            request.getTemplateType() != null
                ? request.getTemplateType()
                : (String) existing.get("template_type"),
            request.getBizType() != null ? request.getBizType() : (String) existing.get("biz_type"),
            request.getEnabled() != null ? request.getEnabled() : (Boolean) existing.get("enabled"),
            version,
            request.getDescription() != null
                ? request.getDescription()
                : (String) existing.get("description")));
    FileTemplateConfigUpsertParam.FormatOptions format =
        new FileTemplateConfigUpsertParam.FormatOptions();
    format.setFileFormatType(
        request.getFileFormatType() != null
            ? request.getFileFormatType()
            : (String) existing.get("file_format_type"));
    format.setCharset(
        request.getCharset() != null ? request.getCharset() : (String) existing.get("charset"));
    format.setTargetCharset(
        request.getTargetCharset() != null
            ? request.getTargetCharset()
            : (String) existing.get("target_charset"));
    format.setWithBom(
        request.getWithBom() != null ? request.getWithBom() : (Boolean) existing.get("with_bom"));
    format.setLineSeparator(
        request.getLineSeparator() != null
            ? request.getLineSeparator()
            : (String) existing.get("line_separator"));
    format.setDelimiter(
        request.getDelimiter() != null
            ? request.getDelimiter()
            : (String) existing.get("delimiter"));
    format.setQuoteChar(
        request.getQuoteChar() != null
            ? request.getQuoteChar()
            : (String) existing.get("quote_char"));
    format.setEscapeChar(
        request.getEscapeChar() != null
            ? request.getEscapeChar()
            : (String) existing.get("escape_char"));
    format.setRecordLength(
        request.getRecordLength() != null
            ? request.getRecordLength()
            : intValue(existing.get("record_length")));
    format.setHeaderRows(
        request.getHeaderRows() != null
            ? request.getHeaderRows()
            : intValue(existing.get("header_rows")));
    format.setFooterRows(
        request.getFooterRows() != null
            ? request.getFooterRows()
            : intValue(existing.get("footer_rows")));
    format.setHeaderTemplateJson(
        request.getHeaderTemplateJson() != null
            ? request.getHeaderTemplateJson()
            : stringValue(existing.get("header_template")));
    format.setTrailerTemplateJson(
        request.getTrailerTemplateJson() != null
            ? request.getTrailerTemplateJson()
            : stringValue(existing.get("trailer_template")));
    format.setChecksumType(
        request.getChecksumType() != null
            ? request.getChecksumType()
            : (String) existing.get("checksum_type"));
    format.setCompressType(
        request.getCompressType() != null
            ? request.getCompressType()
            : (String) existing.get("compress_type"));
    format.setEncryptType(
        request.getEncryptType() != null
            ? request.getEncryptType()
            : (String) existing.get("encrypt_type"));
    format.setNamingRule(
        request.getNamingRule() != null
            ? request.getNamingRule()
            : (String) existing.get("naming_rule"));
    format.setFieldMappingsJson(
        request.getFieldMappingsJson() != null
            ? request.getFieldMappingsJson()
            : stringValue(existing.get("field_mappings")));
    format.setValidationRuleSetJson(
        request.getValidationRuleSetJson() != null
            ? request.getValidationRuleSetJson()
            : stringValue(existing.get("validation_rule_set")));
    param.setFormat(format);
    param.setQuery(
        queryOptions(
            request.getDefaultQueryCode() != null
                ? request.getDefaultQueryCode()
                : (String) existing.get("default_query_code"),
            request.getDefaultQuerySql() != null
                ? request.getDefaultQuerySql()
                : (String) existing.get("default_query_sql"),
            request.getQueryParamSchemaJson() != null
                ? request.getQueryParamSchemaJson()
                : stringValue(existing.get("query_param_schema"))));
    param.setRuntime(
        runtimeOptions(
            request.getStreamingEnabled() != null
                ? request.getStreamingEnabled()
                : (Boolean) existing.get("streaming_enabled"),
            request.getPageSize() != null
                ? request.getPageSize()
                : intValue(existing.get("page_size")),
            request.getFetchSize() != null
                ? request.getFetchSize()
                : intValue(existing.get("fetch_size")),
            request.getChunkSize() != null
                ? request.getChunkSize()
                : intValue(existing.get("chunk_size"))));
    SecurityOptionsInput securityInput =
        SecurityOptionsInput.builder()
            .previewMaskingEnabled(
                request.getPreviewMaskingEnabled() != null
                    ? request.getPreviewMaskingEnabled()
                    : (Boolean) existing.get("preview_masking_enabled"))
            .errorLineMaskingEnabled(
                request.getErrorLineMaskingEnabled() != null
                    ? request.getErrorLineMaskingEnabled()
                    : (Boolean) existing.get("error_line_masking_enabled"))
            .logMaskingEnabled(
                request.getLogMaskingEnabled() != null
                    ? request.getLogMaskingEnabled()
                    : (Boolean) existing.get("log_masking_enabled"))
            .contentEncryptionEnabled(
                request.getContentEncryptionEnabled() != null
                    ? request.getContentEncryptionEnabled()
                    : (Boolean) existing.get("content_encryption_enabled"))
            .encryptionKeyRef(
                request.getEncryptionKeyRef() != null
                    ? request.getEncryptionKeyRef()
                    : (String) existing.get("encryption_key_ref"))
            .downloadRequiresApproval(
                request.getDownloadRequiresApproval() != null
                    ? request.getDownloadRequiresApproval()
                    : (Boolean) existing.get("download_requires_approval"))
            .maskingRuleSet(
                request.getMaskingRuleSet() != null
                    ? request.getMaskingRuleSet()
                    : (String) existing.get("masking_rule_set"))
            .build();
    param.setSecurity(securityOptions(securityInput));
    param.setAudit(auditOptions(operator, operator));
    return param;
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
