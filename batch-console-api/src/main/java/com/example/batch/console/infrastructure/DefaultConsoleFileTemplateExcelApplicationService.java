package com.example.batch.console.infrastructure;

import static com.example.batch.console.support.ConsoleExcelStyles.addBooleanValidation;
import static com.example.batch.console.support.ConsoleExcelStyles.addDropdownValidation;
import static com.example.batch.console.support.ConsoleExcelStyles.createReadmeTitleStyle;
import static com.example.batch.console.support.ConsoleExcelStyles.optionalColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.requiredColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.setReadmeColumnWidth;
import static com.example.batch.console.support.ConsoleExcelStyles.writeHeaders;

import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.FileChecksumType;
import com.example.batch.common.enums.FileCompressType;
import com.example.batch.common.enums.FileEncryptType;
import com.example.batch.common.enums.FileTemplateFormat;
import com.example.batch.common.enums.FileTemplateType;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.console.application.ConsoleFileTemplateExcelApplicationService;
import com.example.batch.console.domain.param.FileTemplateConfigUpsertParam;
import com.example.batch.console.domain.query.FileTemplateConfigQuery;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.FileTemplateConfigMapper;
import com.example.batch.console.support.ConfigChangeLogBuilder;
import com.example.batch.console.support.ConsoleExcelStyles;
import com.example.batch.console.support.ConsoleExcelStyles.ColumnGuide;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.support.ExcelImportStore;
import com.example.batch.console.web.query.FileTemplateQueryRequest;
import com.example.batch.console.web.request.ExcelApplyRequest;
import com.example.batch.console.web.response.ConsoleFileTemplateResponse;
import com.example.batch.console.web.response.ExcelApplyResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link com.example.batch.console.application.ConsoleFileTemplateExcelApplicationService} 的默认实现。
 */
@Service
public class DefaultConsoleFileTemplateExcelApplicationService
    extends AbstractSingleSheetExcelService<
        DefaultConsoleFileTemplateExcelApplicationService.TemplateRow, ConsoleFileTemplateResponse>
    implements ConsoleFileTemplateExcelApplicationService {

  private static final String SHEET_NAME = "file_template_config";

  // ── duplicate literal constants ─────────────────────────────────────────
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

  private static final List<String> COLUMNS =
      List.of(
          "tenant_id",
          "template_code",
          "template_name",
          COL_TEMPLATE_TYPE,
          "biz_type",
          COL_FILE_FORMAT_TYPE,
          "charset",
          "target_charset",
          COL_WITH_BOM,
          "line_separator",
          "delimiter",
          "quote_char",
          "escape_char",
          "record_length",
          "header_rows",
          "footer_rows",
          "header_template",
          "trailer_template",
          COL_CHECKSUM_TYPE,
          COL_COMPRESS_TYPE,
          COL_ENCRYPT_TYPE,
          "naming_rule",
          "field_mappings",
          "validation_rule_set",
          "default_query_code",
          "default_query_sql",
          "query_param_schema",
          COL_STREAMING_ENABLED,
          "page_size",
          "fetch_size",
          "chunk_size",
          "preview_masking_enabled",
          "error_line_masking_enabled",
          "log_masking_enabled",
          "content_encryption_enabled",
          "encryption_key_ref",
          COL_DOWNLOAD_REQUIRES_APPROVAL,
          "masking_rule_set",
          COL_ENABLED,
          "version",
          COL_DESCRIPTION);
  private static final Set<String> FILE_FORMAT_TYPES = DictEnum.codes(FileTemplateFormat.class);
  private static final Set<String> TEMPLATE_TYPES = DictEnum.codes(FileTemplateType.class);
  private static final Set<String> CHECKSUM_TYPES = DictEnum.codes(FileChecksumType.class);
  private static final Set<String> COMPRESS_TYPES = DictEnum.codes(FileCompressType.class);
  private static final Set<String> ENCRYPT_TYPES = DictEnum.codes(FileEncryptType.class);
  private static final int[] BOOLEAN_VALIDATION_COLUMNS = {8, 27, 31, 32, 33, 34, 36, 38};
  private static final Map<String, ColumnGuide> COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry("tenant_id", optionalColumn("当前行所属租户。留空时，上传时自动使用当前租户。", GUIDE_STR, "tenant-a")),
          Map.entry(
              "template_code",
              requiredColumn("模板唯一编码，与 version 一起作为导入匹配键。", GUIDE_STR, "TPL_SETTLEMENT_001")),
          Map.entry("template_name", requiredColumn("控制台展示的模板名称。", GUIDE_STR, "清算导出模板")),
          Map.entry(
              COL_TEMPLATE_TYPE,
              requiredColumn("文件模板在链路中的角色。", GUIDE_ENUM, "EXPORT", "IMPORT", "EXPORT", "SHARED")),
          Map.entry("biz_type", optionalColumn("用于检索和治理的业务分类标签。", GUIDE_STR, "SETTLEMENT")),
          Map.entry(
              COL_FILE_FORMAT_TYPE,
              requiredColumn(
                  "模板生产或消费的物理文件格式。",
                  GUIDE_ENUM,
                  "DELIMITED",
                  "DELIMITED",
                  "FIXED_WIDTH",
                  "EXCEL",
                  "XML",
                  GUIDE_JSON,
                  "BINARY")),
          Map.entry("charset", optionalColumn("文本类文件的源字符集。", GUIDE_STR, "UTF-8")),
          Map.entry("target_charset", optionalColumn("导出转换时的目标字符集。", GUIDE_STR, "GBK")),
          Map.entry(
              COL_WITH_BOM,
              optionalColumn("文本文件是否写入 BOM。", GUIDE_BOOL, GUIDE_FALSE, GUIDE_TRUE, GUIDE_FALSE)),
          Map.entry("line_separator", optionalColumn("文本输出使用的换行符。", GUIDE_STR, "\\n")),
          Map.entry("delimiter", optionalColumn("分隔文本使用的列分隔符。", GUIDE_STR, ",")),
          Map.entry("quote_char", optionalColumn("分隔文本使用的引号字符。", GUIDE_STR, "\"")),
          Map.entry("escape_char", optionalColumn("分隔文本使用的转义字符。", GUIDE_STR, "\\")),
          Map.entry("record_length", optionalColumn("定长文件的记录长度，必须大于等于 0。", GUIDE_INT, "200")),
          Map.entry("header_rows", optionalColumn("文件头行数，必须大于等于 0。", GUIDE_INT, "1")),
          Map.entry("footer_rows", optionalColumn("文件尾行数，必须大于等于 0。", GUIDE_INT, "0")),
          Map.entry(
              "header_template",
              optionalColumn("文件头模板定义，请保持为合法 JSON。", GUIDE_JSON, "{\"recordType\":\"H\"}")),
          Map.entry(
              "trailer_template",
              optionalColumn("文件尾模板定义，请保持为合法 JSON。", GUIDE_JSON, "{\"recordType\":\"T\"}")),
          Map.entry(
              COL_CHECKSUM_TYPE,
              requiredColumn("文件使用的校验算法。", GUIDE_ENUM, GUIDE_NONE, GUIDE_NONE, "MD5", "SHA-256")),
          Map.entry(
              COL_COMPRESS_TYPE,
              requiredColumn("文件使用的压缩算法。", GUIDE_ENUM, "ZIP", GUIDE_NONE, "ZIP", "GZIP")),
          Map.entry(
              COL_ENCRYPT_TYPE,
              requiredColumn(
                  "文件使用的加密算法。", GUIDE_ENUM, GUIDE_NONE, GUIDE_NONE, "AES", "PGP", "CUSTOM")),
          Map.entry("naming_rule", optionalColumn("文件命名规则或命名模板。", "表达式", "${bizDate}_${seq}.csv")),
          Map.entry(
              "field_mappings",
              optionalColumn(
                  "源字段与文件列之间的映射定义，请保持为合法 JSON。",
                  GUIDE_JSON,
                  "[{\"source\":\"amount\",\"target\":\"AMOUNT\"}]")),
          Map.entry(
              "validation_rule_set",
              optionalColumn(
                  "解析或生成时执行的校验规则，请保持为合法 JSON。",
                  GUIDE_JSON,
                  "[{\"field\":\"amount\",\"rule\":\"required\"}]")),
          Map.entry(
              "default_query_code",
              optionalColumn("导出步骤引用的默认查询编码。", GUIDE_STR, "QRY_SETTLEMENT_EXPORT")),
          Map.entry(
              "default_query_sql",
              optionalColumn("模板中可选的内联 SQL。", "SQL", "select * from settlement_result")),
          Map.entry(
              "query_param_schema",
              optionalColumn("查询参数 Schema，请保持为合法 JSON。", GUIDE_JSON, "{\"type\":\"object\"}")),
          Map.entry(
              COL_STREAMING_ENABLED,
              optionalColumn("大结果集导出时是否启用流式处理。", GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
          Map.entry("page_size", optionalColumn("分页读取大小，必须大于等于 0。", GUIDE_INT, "1000")),
          Map.entry("fetch_size", optionalColumn("数据库抓取大小，必须大于等于 0。", GUIDE_INT, "1000")),
          Map.entry("chunk_size", optionalColumn("分段生成文件时的块大小，必须大于等于 0。", GUIDE_INT, "500")),
          Map.entry(
              "preview_masking_enabled",
              optionalColumn("预览结果是否脱敏。", GUIDE_BOOL, GUIDE_FALSE, GUIDE_TRUE, GUIDE_FALSE)),
          Map.entry(
              "error_line_masking_enabled",
              optionalColumn("错误明细导出是否脱敏。", GUIDE_BOOL, GUIDE_FALSE, GUIDE_TRUE, GUIDE_FALSE)),
          Map.entry(
              "log_masking_enabled",
              optionalColumn("日志是否对敏感字段脱敏。", GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
          Map.entry(
              "content_encryption_enabled",
              optionalColumn("存储中的文件内容是否加密。", GUIDE_BOOL, GUIDE_FALSE, GUIDE_TRUE, GUIDE_FALSE)),
          Map.entry(
              "encryption_key_ref",
              optionalColumn("加密所使用的密钥引用。", GUIDE_STR, "kms://file-template/settlement")),
          Map.entry(
              COL_DOWNLOAD_REQUIRES_APPROVAL,
              optionalColumn("下载前是否需要人工审批。", GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
          Map.entry(
              "masking_rule_set",
              optionalColumn("脱敏规则集标识或表达式。", GUIDE_STR, "MASK_RULE_SETTLEMENT")),
          Map.entry(
              COL_ENABLED,
              optionalColumn("文件模板是否启用。", GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
          Map.entry(
              "version", optionalColumn("模板版本号，template_code + version 必须唯一。", GUIDE_INT, "1")),
          Map.entry(COL_DESCRIPTION, optionalColumn("面向运维人员的说明信息。", GUIDE_STR, "清算导出模板")));

  private final FileTemplateConfigMapper fileTemplateConfigMapper;
  private final ConfigChangeLogMapper configChangeLogMapper;

  public DefaultConsoleFileTemplateExcelApplicationService(
      ConsoleTenantGuard tenantGuard,
      ConsoleRequestMetadataResolver requestMetadataResolver,
      ExcelImportStore importStore,
      FileTemplateConfigMapper fileTemplateConfigMapper,
      ConfigChangeLogMapper configChangeLogMapper) {
    super(tenantGuard, requestMetadataResolver, importStore);
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
    String effectiveTenant = resolveTenantField(values, tenantId, issues);
    TemplateRow.TemplateRowBuilder builder =
        TemplateRow.builder().rowNo(rowNo).tenantId(effectiveTenant);
    extractBasicFields(builder, values, issues);
    extractFieldMappings(builder, values, issues);
    extractStepConfig(builder, values, issues);
    extractSecurityConfig(builder, values, issues);
    return builder.build();
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
    fileTemplateConfigMapper.upsertFileTemplateConfig(toUpsertParam(tenantId, row, operatorId));
    return existing == null || existing.isEmpty();
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
    addDropdownValidation(
        sheet,
        3,
        TEMPLATE_TYPES.toArray(String[]::new),
        "template_type 填写提示",
        "请从下拉列表中选择 IMPORT、EXPORT 或 SHARED。");
    addDropdownValidation(
        sheet,
        5,
        FILE_FORMAT_TYPES.toArray(String[]::new),
        "file_format_type 填写提示",
        "请从下拉列表中选择物理文件格式。");
    addDropdownValidation(
        sheet, 18, CHECKSUM_TYPES.toArray(String[]::new), "checksum_type 填写提示", "请从下拉列表中选择校验算法。");
    addDropdownValidation(
        sheet, 19, COMPRESS_TYPES.toArray(String[]::new), "compress_type 填写提示", "请从下拉列表中选择压缩算法。");
    addDropdownValidation(
        sheet, 20, ENCRYPT_TYPES.toArray(String[]::new), "encrypt_type 填写提示", "请从下拉列表中选择加密算法。");
    addBooleanValidation(sheet, BOOLEAN_VALIDATION_COLUMNS, "布尔字段填写提示", "请填写 TRUE 或 FALSE。");
  }

  @Override
  protected void createReadmeSheet(Workbook workbook) {
    Sheet sheet = workbook.createSheet(ConsoleExcelStyles.SHEET_NAME_README);
    setReadmeColumnWidth(sheet);
    CellStyle titleStyle = createReadmeTitleStyle(workbook);
    String[] lines = {
      "文件模板配置维护模板",
      "1. 橙色表头表示必填字段；鼠标悬停表头可查看字段规则与示例。",
      "2. template_code + version 是预览与应用阶段使用的唯一键。",
      "3. 枚举字段与布尔字段已内置下拉值校验。",
      "4. header_template / field_mappings / validation_rule_set / query_param_schema 等 JSON"
          + " 字段必须保持合法 JSON。",
      "5. 导入流程：上传 → 预览 → 应用。"
    };
    for (int i = 0; i < lines.length; i++) {
      Row row = sheet.createRow(i);
      row.createCell(0).setCellValue(lines[i]);
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
      {COL_FILE_FORMAT_TYPE, GUIDE_JSON, "json payload"},
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
        .checksumType(requireEnum(values, COL_CHECKSUM_TYPE, CHECKSUM_TYPES, 32, issues))
        .compressType(requireEnum(values, COL_COMPRESS_TYPE, COMPRESS_TYPES, 32, issues))
        .encryptType(requireEnum(values, COL_ENCRYPT_TYPE, ENCRYPT_TYPES, 32, issues))
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

  private static FileTemplateConfigUpsertParam toUpsertParam(
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

  @Getter
  @Builder
  @Accessors(fluent = true)
  static class TemplateRow {
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
