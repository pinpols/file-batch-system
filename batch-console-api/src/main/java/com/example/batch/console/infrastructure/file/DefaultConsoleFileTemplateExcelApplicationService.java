package com.example.batch.console.infrastructure.file;

import static com.example.batch.console.support.excel.ConsoleExcelStyles.addDropdownValidation;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.createReadmeTitleStyle;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.optionalColumn;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.requiredColumn;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.setReadmeColumnWidth;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.writeHeaders;

import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.FileChecksumType;
import com.example.batch.common.enums.FileCompressType;
import com.example.batch.common.enums.FileEncryptType;
import com.example.batch.common.enums.FileTemplateFormat;
import com.example.batch.common.enums.FileTemplateType;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.application.file.ConsoleFileTemplateExcelApplicationService;
import com.example.batch.console.domain.query.FileTemplateConfigQuery;
import com.example.batch.console.infrastructure.excel.AbstractSingleSheetExcelService;
import com.example.batch.console.infrastructure.excel.ConfigPackageExcelSchema;
import com.example.batch.console.infrastructure.excel.FileTemplateExcelRowParser;
import com.example.batch.console.infrastructure.excel.FileTemplateExcelRowParser.TemplateRow;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.FileTemplateConfigMapper;
import com.example.batch.console.support.ConfigChangeLogBuilder;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.support.excel.ConsoleExcelStyles;
import com.example.batch.console.support.excel.ConsoleExcelStyles.ColumnGuide;
import com.example.batch.console.support.excel.ExcelImportStore;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.query.FileTemplateQueryRequest;
import com.example.batch.console.web.request.excel.ExcelApplyRequest;
import com.example.batch.console.web.response.excel.ExcelApplyResponse;
import com.example.batch.console.web.response.file.ConsoleFileTemplateResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link com.example.batch.console.application.ConsoleFileTemplateExcelApplicationService} 的默认实现。
 */
@Service
public class DefaultConsoleFileTemplateExcelApplicationService
    extends AbstractSingleSheetExcelService<TemplateRow, ConsoleFileTemplateResponse>
    implements ConsoleFileTemplateExcelApplicationService {

  private static final String SHEET_NAME = "file_template_config";

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String FMT_STRING_KEY = "excel.guide.format.string";
  private static final String FMT_BOOLEAN_KEY = "excel.guide.format.boolean";
  private static final String FMT_INTEGER_KEY = "excel.guide.format.integer";
  private static final String COL_DESCRIPTION = "description";
  private static final String GUIDE_FALSE = "FALSE";
  private static final String GUIDE_TRUE = "TRUE";
  private static final String GUIDE_STR = "字符串";
  private static final String COL_FILE_FORMAT_TYPE = "file_format_type";
  private static final String GUIDE_BOOL = "布尔值";
  private static final String GUIDE_NONE = "NONE";
  private static final String COL_ENCRYPT_TYPE = "encrypt_type";
  private static final String COL_ENABLED = "enabled";
  private static final String GUIDE_JSON = "JSON";
  private static final String GUIDE_INT = "整数";
  private static final String COL_TEMPLATE_TYPE = "template_type";
  private static final String COL_CHECKSUM_TYPE = "checksum_type";
  private static final String COL_COMPRESS_TYPE = "compress_type";
  private static final String COL_WITH_BOM = "with_bom";
  private static final String COL_STREAMING_ENABLED = "streaming_enabled";
  private static final String COL_DOWNLOAD_REQUIRES_APPROVAL = "download_requires_approval";
  private static final String GUIDE_ENUM = "枚举";
  private static final String JSON_SHEET_NAME = "file_template_json";
  private static final List<String> JSON_COLUMNS =
      List.of("template_code", "version", "json_field", "json_value");
  private static final List<String> JSON_FIELDS =
      List.of(
          "header_template",
          "trailer_template",
          "field_mappings",
          "validation_rule_set",
          "query_param_schema");

  private static final List<String> COLUMNS = ConfigPackageExcelSchema.FileTemplate.COLUMNS;
  private static final Set<String> FILE_FORMAT_TYPES = DictEnum.codes(FileTemplateFormat.class);
  private static final Set<String> TEMPLATE_TYPES = DictEnum.codes(FileTemplateType.class);
  private static final Set<String> CHECKSUM_TYPES = DictEnum.codes(FileChecksumType.class);
  private static final Set<String> COMPRESS_TYPES = DictEnum.codes(FileCompressType.class);
  private static final Set<String> ENCRYPT_TYPES = DictEnum.codes(FileEncryptType.class);
  private static final int[] BOOLEAN_VALIDATION_COLUMNS = {8, 27, 31, 32, 33, 34, 36, 38};
  private static final Map<String, ColumnGuide> COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry(
              "tenant_id",
              optionalColumn("excel.template.tenant_id.desc", FMT_STRING_KEY, "tenant-a")),
          Map.entry(
              "template_code",
              requiredColumn(
                  "excel.template.template_code.desc", FMT_STRING_KEY, "TPL_SETTLEMENT_001")),
          Map.entry(
              "template_name",
              requiredColumn("excel.template.template_name.desc", FMT_STRING_KEY, "清算导出模板")),
          Map.entry(
              COL_TEMPLATE_TYPE,
              requiredColumn(
                  "excel.template.template_type.desc",
                  "excel.guide.format.enum",
                  "EXPORT",
                  "IMPORT",
                  "EXPORT",
                  "SHARED")),
          Map.entry(
              "biz_type",
              optionalColumn("excel.template.biz_type.desc", FMT_STRING_KEY, "SETTLEMENT")),
          Map.entry(
              COL_FILE_FORMAT_TYPE,
              requiredColumn(
                  "excel.template.file_format_type.desc",
                  "excel.guide.format.enum",
                  "DELIMITED",
                  "DELIMITED",
                  "FIXED_WIDTH",
                  "EXCEL",
                  "XML",
                  "JSON",
                  "BINARY")),
          Map.entry(
              "charset", optionalColumn("excel.template.charset.desc", FMT_STRING_KEY, "UTF-8")),
          Map.entry(
              "target_charset",
              optionalColumn("excel.template.target_charset.desc", FMT_STRING_KEY, "GBK")),
          Map.entry(
              COL_WITH_BOM,
              optionalColumn(
                  "excel.template.with_bom.desc",
                  FMT_BOOLEAN_KEY,
                  GUIDE_FALSE,
                  GUIDE_TRUE,
                  GUIDE_FALSE)),
          Map.entry(
              "line_separator",
              optionalColumn("excel.template.line_separator.desc", FMT_STRING_KEY, "\\n")),
          Map.entry(
              "delimiter", optionalColumn("excel.template.delimiter.desc", FMT_STRING_KEY, ",")),
          Map.entry(
              "quote_char", optionalColumn("excel.template.quote_char.desc", FMT_STRING_KEY, "\"")),
          Map.entry(
              "escape_char",
              optionalColumn("excel.template.escape_char.desc", FMT_STRING_KEY, "\\")),
          Map.entry(
              "record_length",
              optionalColumn("excel.template.record_length.desc", FMT_INTEGER_KEY, "200")),
          Map.entry(
              "header_rows",
              optionalColumn("excel.template.header_rows.desc", FMT_INTEGER_KEY, "1")),
          Map.entry(
              "footer_rows",
              optionalColumn("excel.template.footer_rows.desc", FMT_INTEGER_KEY, "0")),
          Map.entry(
              "header_template",
              optionalColumn(
                  "excel.template.header_template.desc",
                  "excel.guide.format.json",
                  "{\"recordType\":\"H\"}")),
          Map.entry(
              "trailer_template",
              optionalColumn(
                  "excel.template.trailer_template.desc",
                  "excel.guide.format.json",
                  "{\"recordType\":\"T\"}")),
          Map.entry(
              COL_CHECKSUM_TYPE,
              requiredColumn(
                  "excel.template.checksum_type.desc",
                  "excel.guide.format.enum",
                  GUIDE_NONE,
                  GUIDE_NONE,
                  "MD5",
                  "SHA-256")),
          Map.entry(
              COL_COMPRESS_TYPE,
              requiredColumn(
                  "excel.template.compress_type.desc",
                  "excel.guide.format.enum",
                  "ZIP",
                  GUIDE_NONE,
                  "ZIP",
                  "GZIP")),
          Map.entry(
              COL_ENCRYPT_TYPE,
              requiredColumn(
                  "excel.template.encrypt_type.desc",
                  "excel.guide.format.enum",
                  GUIDE_NONE,
                  GUIDE_NONE,
                  "AES",
                  "PGP",
                  "CUSTOM")),
          Map.entry(
              "naming_rule",
              optionalColumn(
                  "excel.template.naming_rule.desc",
                  "excel.guide.format.expression",
                  "${bizDate}_${seq}.csv")),
          Map.entry(
              "field_mappings",
              optionalColumn(
                  "excel.template.field_mappings.desc",
                  "excel.guide.format.json",
                  "[{\"source\":\"amount\",\"target\":\"AMOUNT\"}]")),
          Map.entry(
              "validation_rule_set",
              optionalColumn(
                  "excel.template.validation_rule_set.desc",
                  "excel.guide.format.json",
                  "[{\"field\":\"amount\",\"rule\":\"required\"}]")),
          Map.entry(
              "default_query_code",
              optionalColumn(
                  "excel.template.default_query_code.desc",
                  FMT_STRING_KEY,
                  "QRY_SETTLEMENT_EXPORT")),
          Map.entry(
              "default_query_sql",
              optionalColumn(
                  "excel.template.default_query_sql.desc",
                  "excel.guide.format.sql",
                  "select * from settlement_result")),
          Map.entry(
              "query_param_schema",
              optionalColumn(
                  "excel.template.query_param_schema.desc",
                  "excel.guide.format.json",
                  "{\"type\":\"object\"}")),
          Map.entry(
              COL_STREAMING_ENABLED,
              optionalColumn(
                  "excel.template.streaming_enabled.desc",
                  FMT_BOOLEAN_KEY,
                  GUIDE_TRUE,
                  GUIDE_TRUE,
                  GUIDE_FALSE)),
          Map.entry(
              "page_size",
              optionalColumn("excel.template.page_size.desc", FMT_INTEGER_KEY, "1000")),
          Map.entry(
              "fetch_size",
              optionalColumn("excel.template.fetch_size.desc", FMT_INTEGER_KEY, "1000")),
          Map.entry(
              "chunk_size",
              optionalColumn("excel.template.chunk_size.desc", FMT_INTEGER_KEY, "500")),
          Map.entry(
              "preview_masking_enabled",
              optionalColumn(
                  "excel.template.preview_masking_enabled.desc",
                  FMT_BOOLEAN_KEY,
                  GUIDE_FALSE,
                  GUIDE_TRUE,
                  GUIDE_FALSE)),
          Map.entry(
              "error_line_masking_enabled",
              optionalColumn(
                  "excel.template.error_line_masking_enabled.desc",
                  FMT_BOOLEAN_KEY,
                  GUIDE_FALSE,
                  GUIDE_TRUE,
                  GUIDE_FALSE)),
          Map.entry(
              "log_masking_enabled",
              optionalColumn(
                  "excel.template.log_masking_enabled.desc",
                  FMT_BOOLEAN_KEY,
                  GUIDE_TRUE,
                  GUIDE_TRUE,
                  GUIDE_FALSE)),
          Map.entry(
              "content_encryption_enabled",
              optionalColumn(
                  "excel.template.content_encryption_enabled.desc",
                  FMT_BOOLEAN_KEY,
                  GUIDE_FALSE,
                  GUIDE_TRUE,
                  GUIDE_FALSE)),
          Map.entry(
              "encryption_key_ref",
              optionalColumn(
                  "excel.template.encryption_key_ref.desc",
                  FMT_STRING_KEY,
                  "kms://file-template/settlement")),
          Map.entry(
              COL_DOWNLOAD_REQUIRES_APPROVAL,
              optionalColumn(
                  "excel.template.download_requires_approval.desc",
                  FMT_BOOLEAN_KEY,
                  GUIDE_TRUE,
                  GUIDE_TRUE,
                  GUIDE_FALSE)),
          Map.entry(
              "masking_rule_set",
              optionalColumn(
                  "excel.template.masking_rule_set.desc", FMT_STRING_KEY, "MASK_RULE_SETTLEMENT")),
          Map.entry(
              COL_ENABLED,
              optionalColumn(
                  "excel.template.enabled.desc",
                  FMT_BOOLEAN_KEY,
                  GUIDE_TRUE,
                  GUIDE_TRUE,
                  GUIDE_FALSE)),
          Map.entry("version", optionalColumn("excel.template.version.desc", FMT_INTEGER_KEY, "1")),
          Map.entry(
              COL_DESCRIPTION,
              optionalColumn("excel.template.description.desc", FMT_STRING_KEY, "清算导出模板")));

  private final FileTemplateConfigMapper fileTemplateConfigMapper;
  private final ConfigChangeLogMapper configChangeLogMapper;

  public DefaultConsoleFileTemplateExcelApplicationService(
      ConsoleTenantGuard tenantGuard,
      ConsoleRequestMetadataResolver requestMetadataResolver,
      ExcelImportStore importStore,
      BatchDateTimeSupport dateTimeSupport,
      MessageSource messageSource,
      FileTemplateConfigMapper fileTemplateConfigMapper,
      ConfigChangeLogMapper configChangeLogMapper) {
    super(tenantGuard, requestMetadataResolver, importStore, dateTimeSupport, messageSource);
    this.fileTemplateConfigMapper = fileTemplateConfigMapper;
    this.configChangeLogMapper = configChangeLogMapper;
  }

  @Override
  public ResponseEntity<InputStreamResource> exportFileTemplates(FileTemplateQueryRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    FileTemplateConfigQuery exportQuery =
        FileTemplateConfigQuery.builder()
            .tenantId(tenantId)
            .keyword(request.getKeyword())
            .templateCode(request.getTemplateCode())
            .templateName(request.getTemplateName())
            .templateType(request.getTemplateType())
            .bizType(request.getBizType())
            .enabled(request.getEnabled())
            .build();
    List<Map<String, Object>> rows = fileTemplateConfigMapper.selectByQuery(exportQuery);
    return doExport(tenantId, rows);
  }

  @Override
  @Transactional
  public ExcelApplyResponse apply(String uploadToken, ExcelApplyRequest request) {
    return doApply(uploadToken, request.getReason());
  }

  @Override
  protected String sheetName() {
    return SHEET_NAME;
  }

  @Override
  protected List<String> columns() {
    return COLUMNS;
  }

  @Override
  protected Map<String, ColumnGuide> columnGuides() {
    return COLUMN_GUIDES;
  }

  @Override
  protected TemplateRow parseRow(
      String tenantId, int rowNo, Map<String, String> values, List<String> issues) {
    return FileTemplateExcelRowParser.parseRow(tenantId, rowNo, values, issues);
  }

  @Override
  protected String rowUniqueKey(TemplateRow row) {
    return row.templateCode() + "#" + row.version();
  }

  @Override
  protected ConsoleFileTemplateResponse toResponse(TemplateRow row) {
    return new ConsoleFileTemplateResponse(
        null,
        row.tenantId(),
        row.templateCode(),
        row.templateName(),
        row.templateType(),
        row.bizType(),
        row.fileFormatType(),
        row.charset(),
        row.targetCharset(),
        row.withBom(),
        row.lineSeparator(),
        row.delimiter(),
        row.quoteChar(),
        row.escapeChar(),
        row.recordLength(),
        row.headerRows(),
        row.footerRows(),
        row.headerTemplateJson(),
        row.trailerTemplateJson(),
        row.checksumType(),
        row.compressType(),
        row.encryptType(),
        row.namingRule(),
        row.fieldMappingsJson(),
        row.validationRuleSetJson(),
        row.defaultQueryCode(),
        row.defaultQuerySql(),
        row.queryParamSchemaJson(),
        row.streamingEnabled(),
        row.pageSize(),
        row.fetchSize(),
        row.chunkSize(),
        row.previewMaskingEnabled(),
        row.errorLineMaskingEnabled(),
        row.logMaskingEnabled(),
        row.contentEncryptionEnabled(),
        row.encryptionKeyRef(),
        row.downloadRequiresApproval(),
        row.maskingRuleSet(),
        row.enabled(),
        row.version(),
        row.description(),
        null,
        null,
        null,
        null);
  }

  @Override
  protected boolean upsertRow(TemplateRow row, String tenantId, String operatorId) {
    Map<String, Object> existing =
        fileTemplateConfigMapper.selectByUniqueKey(tenantId, row.templateCode(), row.version());
    fileTemplateConfigMapper.upsertFileTemplateConfig(
        FileTemplateExcelRowParser.toUpsertParam(tenantId, row, operatorId));
    return existing == null || existing.isEmpty();
  }

  @Override
  protected boolean rowExists(TemplateRow row, String tenantId) {
    Map<String, Object> existing =
        fileTemplateConfigMapper.selectByUniqueKey(tenantId, row.templateCode(), row.version());
    return existing != null && !existing.isEmpty();
  }

  @Override
  protected void logChange(
      String tenantId,
      TemplateRow row,
      String reason,
      String operatorId,
      String traceId,
      String action) {
    configChangeLogMapper.insertConfigChangeLog(
        ConfigChangeLogBuilder.create(tenantId, operatorId, traceId)
            .forType("FILE_TEMPLATE")
            .withKey(row.templateCode())
            .versionNo(row.version())
            .action(action)
            .summary(
                changeSummaryJson(
                    reason,
                    mapOf(
                        "templateName",
                        row.templateName(),
                        COL_ENABLED,
                        row.enabled(),
                        "fileFormatType",
                        row.fileFormatType())))
            .build());
  }

  @Override
  protected void applyValidations(Sheet sheet) {
    Locale locale = LocaleContextHolder.getLocale();
    addDropdownValidation(
        sheet,
        3,
        TEMPLATE_TYPES.toArray(String[]::new),
        "excel.template.template_type.prompt_title",
        "excel.template.template_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        5,
        FILE_FORMAT_TYPES.toArray(String[]::new),
        "excel.template.file_format_type.prompt_title",
        "excel.template.file_format_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        18,
        CHECKSUM_TYPES.toArray(String[]::new),
        "excel.template.checksum_type.prompt_title",
        "excel.template.checksum_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        19,
        COMPRESS_TYPES.toArray(String[]::new),
        "excel.template.compress_type.prompt_title",
        "excel.template.compress_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        20,
        ENCRYPT_TYPES.toArray(String[]::new),
        "excel.template.encrypt_type.prompt_title",
        "excel.template.encrypt_type.prompt_box",
        messageSource,
        locale);
    for (int col : BOOLEAN_VALIDATION_COLUMNS) {
      addDropdownValidation(
          sheet,
          col,
          new String[] {"TRUE", "FALSE"},
          "excel.common.enabled.prompt_title",
          "excel.common.enabled.prompt_box",
          messageSource,
          locale);
    }
  }

  @Override
  protected void createExtraWorkbookSheets(Workbook workbook, List<Map<String, Object>> rows) {
    Locale locale = LocaleContextHolder.getLocale();
    Sheet sheet = workbook.createSheet(JSON_SHEET_NAME);
    sheet.createFreezePane(0, 1, 0, 1);
    writeHeaders(sheet, JSON_COLUMNS, ConsoleExcelStyles.createHeaderStyle(workbook));
    addDropdownValidation(
        sheet,
        2,
        JSON_FIELDS.toArray(String[]::new),
        "excel.template.json_field.prompt_title",
        "excel.template.json_field.prompt_box",
        messageSource,
        locale);
    int rowIndex = 1;
    for (Map<String, Object> sourceRow : rows) {
      for (String jsonField : JSON_FIELDS) {
        Object value = sourceRow.get(jsonField);
        if (value == null || String.valueOf(value).isBlank()) {
          continue;
        }
        Row row = sheet.createRow(rowIndex++);
        row.createCell(0).setCellValue(String.valueOf(sourceRow.getOrDefault("template_code", "")));
        row.createCell(1).setCellValue(String.valueOf(sourceRow.getOrDefault("version", "1")));
        row.createCell(2).setCellValue(jsonField);
        row.createCell(3).setCellValue(ConsoleExcelStyles.escapeFormula(String.valueOf(value)));
      }
    }
    if (rows.isEmpty()) {
      Row row = sheet.createRow(rowIndex);
      row.createCell(0).setCellValue("TPL_SETTLEMENT_001");
      row.createCell(1).setCellValue("1");
      row.createCell(2).setCellValue("field_mappings");
      row.createCell(3).setCellValue("[{\"source\":\"amount\",\"target\":\"AMOUNT\"}]");
    }
    sheet.setColumnWidth(0, 32 * 256);
    sheet.setColumnWidth(1, 12 * 256);
    sheet.setColumnWidth(2, 28 * 256);
    sheet.setColumnWidth(3, 80 * 256);
  }

  @Override
  protected void createReadmeSheet(Workbook workbook) {
    Locale locale = LocaleContextHolder.getLocale();
    Sheet sheet = workbook.createSheet(ConsoleExcelStyles.SHEET_NAME_README);
    setReadmeColumnWidth(sheet);
    CellStyle titleStyle = createReadmeTitleStyle(workbook);
    String[] keys = {
      "excel.template.readme.title",
      "excel.template.readme.line1",
      "excel.template.readme.line2",
      "excel.template.readme.line3",
      "excel.template.readme.line4",
      "excel.template.readme.line5"
    };
    for (int i = 0; i < keys.length; i++) {
      Row row = sheet.createRow(i);
      row.createCell(0).setCellValue(messageSource.getMessage(keys[i], null, keys[i], locale));
      if (i == 0) {
        row.getCell(0).setCellStyle(titleStyle);
      }
    }
  }

  @Override
  protected void createDictSheet(Workbook workbook) {
    Sheet sheet = workbook.createSheet(ConsoleExcelStyles.SHEET_NAME_DICT);
    sheet.createFreezePane(0, 1, 0, 1);
    CellStyle dictHeaderStyle = ConsoleExcelStyles.createHeaderStyle(workbook);
    writeHeaders(sheet, List.of("field", "value", COL_DESCRIPTION), dictHeaderStyle);
    String[][] rows = {
      {COL_TEMPLATE_TYPE, "IMPORT", "import template"},
      {COL_TEMPLATE_TYPE, "EXPORT", "export template"},
      {COL_TEMPLATE_TYPE, "SHARED", "shared template"},
      {COL_FILE_FORMAT_TYPE, "DELIMITED", "delimited text"},
      {COL_FILE_FORMAT_TYPE, "FIXED_WIDTH", "fixed width text"},
      {COL_FILE_FORMAT_TYPE, "EXCEL", "excel workbook"},
      {COL_FILE_FORMAT_TYPE, "XML", "xml payload"},
      {COL_FILE_FORMAT_TYPE, "excel.guide.format.json", "json payload"},
      {COL_FILE_FORMAT_TYPE, "BINARY", "binary payload"},
      {COL_CHECKSUM_TYPE, GUIDE_NONE, "no checksum"},
      {COL_CHECKSUM_TYPE, "MD5", "md5 checksum"},
      {COL_CHECKSUM_TYPE, "SHA-256", "sha-256 checksum"},
      {COL_COMPRESS_TYPE, GUIDE_NONE, "no compression"},
      {COL_COMPRESS_TYPE, "ZIP", "zip compression"},
      {COL_COMPRESS_TYPE, "GZIP", "gzip compression"},
      {COL_ENCRYPT_TYPE, GUIDE_NONE, "no encryption"},
      {COL_ENCRYPT_TYPE, "AES", "aes encryption"},
      {COL_ENCRYPT_TYPE, "PGP", "pgp encryption"},
      {COL_ENCRYPT_TYPE, "CUSTOM", "custom encryption"},
      {COL_ENABLED, GUIDE_TRUE, COL_ENABLED},
      {COL_ENABLED, GUIDE_FALSE, "disabled"},
      {COL_WITH_BOM, GUIDE_TRUE, "with bom"},
      {COL_WITH_BOM, GUIDE_FALSE, "without bom"},
      {COL_STREAMING_ENABLED, GUIDE_TRUE, "streaming"},
      {COL_STREAMING_ENABLED, GUIDE_FALSE, "non-streaming"},
      {COL_DOWNLOAD_REQUIRES_APPROVAL, GUIDE_TRUE, "requires approval"},
      {COL_DOWNLOAD_REQUIRES_APPROVAL, GUIDE_FALSE, "no approval"}
    };
    for (int i = 0; i < rows.length; i++) {
      Row row = sheet.createRow(i + 1);
      row.createCell(0).setCellValue(rows[i][0]);
      row.createCell(1).setCellValue(rows[i][1]);
      row.createCell(2).setCellValue(rows[i][2]);
    }
    sheet.setColumnWidth(0, 28 * 256);
    sheet.setColumnWidth(1, 20 * 256);
    sheet.setColumnWidth(2, 36 * 256);
  }
}
