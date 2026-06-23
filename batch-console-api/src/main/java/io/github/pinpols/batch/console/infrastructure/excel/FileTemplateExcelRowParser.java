package io.github.pinpols.batch.console.infrastructure.excel;

import static io.github.pinpols.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.optionalBoolean;
import static io.github.pinpols.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.optionalEnum;
import static io.github.pinpols.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.optionalInteger;
import static io.github.pinpols.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.optionalJson;
import static io.github.pinpols.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.optionalText;
import static io.github.pinpols.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.requireEnum;
import static io.github.pinpols.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.requireText;
import static io.github.pinpols.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.resolveTenantField;

import io.github.pinpols.batch.common.enums.DictEnum;
import io.github.pinpols.batch.common.enums.FileChecksumType;
import io.github.pinpols.batch.common.enums.FileCompressType;
import io.github.pinpols.batch.common.enums.FileEncryptType;
import io.github.pinpols.batch.common.enums.FileTemplateFormat;
import io.github.pinpols.batch.common.enums.FileTemplateType;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.console.domain.file.param.FileTemplateConfigUpsertParam;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * file_template_config Excel 行解析 + upsert param 转换的纯函数工具类。
 *
 * <p>从 {@code DefaultConsoleFileTemplateExcelApplicationService} 抽离，让 9+2 租户配置包 （{@code
 * DefaultConsoleTenantConfigPackageExcelApplicationService}）和独立 file_template Excel 入口
 * 共用同一份解析与转换逻辑，避免双线漂移（9+2 设计文档 §实施步骤 P0-2）。
 *
 * <p>本类**无依赖**：所有方法 static，输入皆通过参数传入；不持有 mapper / dataSource，所以两个调用方各自管控事务 与 mapper upsert。
 */
public final class FileTemplateExcelRowParser {

  /** file_template_config 表名（与 sheet 名一致）。 */
  public static final String SHEET_NAME = "file_template_config";

  private static final Set<String> FILE_FORMAT_TYPES = DictEnum.codes(FileTemplateFormat.class);
  private static final Set<String> TEMPLATE_TYPES = DictEnum.codes(FileTemplateType.class);
  private static final Set<String> CHECKSUM_TYPES = DictEnum.codes(FileChecksumType.class);
  private static final Set<String> COMPRESS_TYPES = DictEnum.codes(FileCompressType.class);
  private static final Set<String> ENCRYPT_TYPES = DictEnum.codes(FileEncryptType.class);

  private static final String COL_TEMPLATE_TYPE = "template_type";
  private static final String COL_FILE_FORMAT_TYPE = "file_format_type";
  private static final String COL_CHECKSUM_TYPE = "checksum_type";
  private static final String COL_COMPRESS_TYPE = "compress_type";
  private static final String COL_ENCRYPT_TYPE = "encrypt_type";
  private static final String COL_WITH_BOM = "with_bom";
  private static final String COL_STREAMING_ENABLED = "streaming_enabled";
  private static final String COL_DOWNLOAD_REQUIRES_APPROVAL = "download_requires_approval";
  private static final String COL_ENABLED = "enabled";
  private static final String COL_DESCRIPTION = "description";

  private FileTemplateExcelRowParser() {}

  /**
   * 解析一行 file_template_config Excel 数据。
   *
   * @param tenantId 默认租户（行内 tenant_id 留空时回退）
   * @param rowNo 行号（用于 issue 定位）
   * @param values 列名 → 单元格文本
   * @param issues 校验问题集合（in-out，解析失败时追加描述）
   * @return 解析后的 TemplateRow；即使有 issue 也返回（部分字段可能为空）
   */
  public static TemplateRow parseRow(
      String tenantId, int rowNo, Map<String, String> values, List<String> issues) {
    String effectiveTenant = resolveTenantField(values, tenantId, issues);
    TemplateRow.TemplateRowBuilder builder =
        TemplateRow.builder().rowNo(rowNo).tenantId(effectiveTenant);
    extractBasicFields(builder, values, issues);
    extractFieldMappings(builder, values, issues);
    extractStepConfig(builder, values, issues);
    extractSecurityConfig(builder, values, issues);
    return builder.build();
  }

  /** 把 TemplateRow 转 mapper 用的 upsert param。 */
  public static FileTemplateConfigUpsertParam toUpsertParam(
      String tenantId, TemplateRow row, String operatorId) {
    FileTemplateConfigUpsertParam param = new FileTemplateConfigUpsertParam();
    param.setTenantId(tenantId);
    param.setTemplateCode(row.templateCode());

    FileTemplateConfigUpsertParam.BasicInfo basicInfo =
        new FileTemplateConfigUpsertParam.BasicInfo();
    basicInfo.setTemplateName(row.templateName());
    basicInfo.setTemplateType(row.templateType());
    basicInfo.setBizType(row.bizType());
    basicInfo.setEnabled(row.enabled());
    basicInfo.setVersion(row.version());
    basicInfo.setDescription(row.description());
    param.setBasicInfo(basicInfo);

    FileTemplateConfigUpsertParam.FormatOptions format =
        new FileTemplateConfigUpsertParam.FormatOptions();
    format.setFileFormatType(row.fileFormatType());
    format.setCharset(row.charset());
    format.setTargetCharset(row.targetCharset());
    format.setWithBom(row.withBom());
    format.setLineSeparator(row.lineSeparator());
    format.setDelimiter(row.delimiter());
    format.setQuoteChar(row.quoteChar());
    format.setEscapeChar(row.escapeChar());
    format.setRecordLength(row.recordLength());
    format.setHeaderRows(row.headerRows());
    format.setFooterRows(row.footerRows());
    format.setHeaderTemplateJson(row.headerTemplateJson());
    format.setTrailerTemplateJson(row.trailerTemplateJson());
    format.setChecksumType(row.checksumType());
    format.setCompressType(row.compressType());
    format.setEncryptType(row.encryptType());
    format.setNamingRule(row.namingRule());
    format.setFieldMappingsJson(row.fieldMappingsJson());
    format.setValidationRuleSetJson(row.validationRuleSetJson());
    param.setFormat(format);

    FileTemplateConfigUpsertParam.QueryOptions query =
        new FileTemplateConfigUpsertParam.QueryOptions();
    query.setDefaultQueryCode(row.defaultQueryCode());
    query.setDefaultQuerySql(row.defaultQuerySql());
    query.setQueryParamSchemaJson(row.queryParamSchemaJson());
    param.setQuery(query);

    FileTemplateConfigUpsertParam.RuntimeOptions runtime =
        new FileTemplateConfigUpsertParam.RuntimeOptions();
    runtime.setStreamingEnabled(row.streamingEnabled());
    runtime.setPageSize(row.pageSize());
    runtime.setFetchSize(row.fetchSize());
    runtime.setChunkSize(row.chunkSize());
    param.setRuntime(runtime);

    FileTemplateConfigUpsertParam.SecurityOptions security =
        new FileTemplateConfigUpsertParam.SecurityOptions();
    security.setPreviewMaskingEnabled(row.previewMaskingEnabled());
    security.setErrorLineMaskingEnabled(row.errorLineMaskingEnabled());
    security.setLogMaskingEnabled(row.logMaskingEnabled());
    security.setContentEncryptionEnabled(row.contentEncryptionEnabled());
    security.setEncryptionKeyRef(row.encryptionKeyRef());
    security.setDownloadRequiresApproval(row.downloadRequiresApproval());
    security.setMaskingRuleSet(row.maskingRuleSet());
    param.setSecurity(security);

    FileTemplateConfigUpsertParam.AuditOptions audit =
        new FileTemplateConfigUpsertParam.AuditOptions();
    audit.setCreatedBy(ConsoleTextSanitizer.safeInput(operatorId, 64));
    audit.setUpdatedBy(ConsoleTextSanitizer.safeInput(operatorId, 64));
    param.setAudit(audit);
    return param;
  }

  // ─── 私有 extract 拆分（保持原逻辑分组，避免单方法过长触发 PMD NcssCount） ──────────────

  private static void extractBasicFields(
      TemplateRow.TemplateRowBuilder builder, Map<String, String> values, List<String> issues) {
    builder
        .templateCode(requireText(values, "template_code", 128, issues))
        .templateName(requireText(values, "template_name", 256, issues))
        .templateType(requireEnum(values, COL_TEMPLATE_TYPE, TEMPLATE_TYPES, 32, issues))
        .bizType(optionalText(values, "biz_type", 64, issues))
        .fileFormatType(requireEnum(values, COL_FILE_FORMAT_TYPE, FILE_FORMAT_TYPES, 32, issues))
        .enabled(optionalBoolean(values, COL_ENABLED, true, issues))
        .version(optionalInteger(values, "version", 1, 1, issues))
        .description(optionalText(values, COL_DESCRIPTION, 1024, issues));
  }

  private static void extractFieldMappings(
      TemplateRow.TemplateRowBuilder builder, Map<String, String> values, List<String> issues) {
    builder
        .charset(optionalText(values, "charset", 32, issues))
        .targetCharset(optionalText(values, "target_charset", 32, issues))
        .withBom(optionalBoolean(values, COL_WITH_BOM, false, issues))
        .lineSeparator(optionalText(values, "line_separator", 16, issues))
        .delimiter(optionalText(values, "delimiter", 8, issues))
        .quoteChar(optionalText(values, "quote_char", 8, issues))
        .escapeChar(optionalText(values, "escape_char", 8, issues))
        .recordLength(optionalInteger(values, "record_length", 0, 0, issues))
        .headerRows(optionalInteger(values, "header_rows", 0, 0, issues))
        .footerRows(optionalInteger(values, "footer_rows", 0, 0, issues))
        .headerTemplateJson(optionalJson(values, "header_template", issues))
        .trailerTemplateJson(optionalJson(values, "trailer_template", issues))
        .checksumType(optionalEnum(values, COL_CHECKSUM_TYPE, CHECKSUM_TYPES, 32, "NONE", issues))
        .compressType(optionalEnum(values, COL_COMPRESS_TYPE, COMPRESS_TYPES, 32, "NONE", issues))
        .encryptType(optionalEnum(values, COL_ENCRYPT_TYPE, ENCRYPT_TYPES, 32, "NONE", issues))
        .namingRule(optionalText(values, "naming_rule", 512, issues))
        .fieldMappingsJson(optionalJson(values, "field_mappings", issues))
        .validationRuleSetJson(optionalJson(values, "validation_rule_set", issues));
  }

  private static void extractStepConfig(
      TemplateRow.TemplateRowBuilder builder, Map<String, String> values, List<String> issues) {
    builder
        .defaultQueryCode(optionalText(values, "default_query_code", 128, issues))
        .defaultQuerySql(optionalText(values, "default_query_sql", 10000, issues))
        .queryParamSchemaJson(optionalJson(values, "query_param_schema", issues))
        .streamingEnabled(optionalBoolean(values, COL_STREAMING_ENABLED, true, issues))
        .pageSize(optionalInteger(values, "page_size", 0, 1000, issues))
        .fetchSize(optionalInteger(values, "fetch_size", 0, 1000, issues))
        .chunkSize(optionalInteger(values, "chunk_size", 0, 500, issues));
  }

  private static void extractSecurityConfig(
      TemplateRow.TemplateRowBuilder builder, Map<String, String> values, List<String> issues) {
    builder
        .previewMaskingEnabled(optionalBoolean(values, "preview_masking_enabled", false, issues))
        .errorLineMaskingEnabled(
            optionalBoolean(values, "error_line_masking_enabled", false, issues))
        .logMaskingEnabled(optionalBoolean(values, "log_masking_enabled", false, issues))
        .contentEncryptionEnabled(
            optionalBoolean(values, "content_encryption_enabled", false, issues))
        .encryptionKeyRef(optionalText(values, "encryption_key_ref", 128, issues))
        .downloadRequiresApproval(
            optionalBoolean(values, COL_DOWNLOAD_REQUIRES_APPROVAL, false, issues))
        .maskingRuleSet(optionalText(values, "masking_rule_set", 256, issues));
  }

  /** Excel 行模型，单一来源；独立 service 与 9+2 config-package service 共用。 */
  @Getter
  @Builder
  @Accessors(fluent = true)
  public static class TemplateRow {
    private final Integer rowNo;
    private final String tenantId;
    private final String templateCode;
    private final String templateName;
    private final String templateType;
    private final String bizType;
    private final String fileFormatType;
    private final String charset;
    private final String targetCharset;
    private final Boolean withBom;
    private final String lineSeparator;
    private final String delimiter;
    private final String quoteChar;
    private final String escapeChar;
    private final Integer recordLength;
    private final Integer headerRows;
    private final Integer footerRows;
    private final String headerTemplateJson;
    private final String trailerTemplateJson;
    private final String checksumType;
    private final String compressType;
    private final String encryptType;
    private final String namingRule;
    private final String fieldMappingsJson;
    private final String validationRuleSetJson;
    private final String defaultQueryCode;
    private final String defaultQuerySql;
    private final String queryParamSchemaJson;
    private final Boolean streamingEnabled;
    private final Integer pageSize;
    private final Integer fetchSize;
    private final Integer chunkSize;
    private final Boolean previewMaskingEnabled;
    private final Boolean errorLineMaskingEnabled;
    private final Boolean logMaskingEnabled;
    private final Boolean contentEncryptionEnabled;
    private final String encryptionKeyRef;
    private final Boolean downloadRequiresApproval;
    private final String maskingRuleSet;
    private final Boolean enabled;
    private final Integer version;
    private final String description;
  }
}
