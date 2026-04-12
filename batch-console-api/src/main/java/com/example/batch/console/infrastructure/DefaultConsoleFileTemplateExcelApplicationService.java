package com.example.batch.console.infrastructure;

import static com.example.batch.console.support.ConsoleExcelStyles.addBooleanValidation;
import static com.example.batch.console.support.ConsoleExcelStyles.addDropdownValidation;
import static com.example.batch.console.support.ConsoleExcelStyles.createReadmeTitleStyle;
import static com.example.batch.console.support.ConsoleExcelStyles.optionalColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.requiredColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.setWidths;
import static com.example.batch.console.support.ConsoleExcelStyles.writeHeaders;
import static com.example.batch.console.support.ConsoleExcelStyles.writeTemplateHeaders;

import com.example.batch.common.enums.FileChecksumType;
import com.example.batch.common.enums.FileCompressType;
import com.example.batch.common.enums.FileEncryptType;
import com.example.batch.common.enums.FileTemplateFormat;
import com.example.batch.common.enums.FileTemplateType;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.application.ConsoleFileTemplateExcelApplicationService;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.FileTemplateConfigMapper;
import com.example.batch.console.mapper.param.FileTemplateConfigUpsertParam;
import com.example.batch.console.support.ConsoleExcelPreviewWorkbookSupport;
import com.example.batch.console.support.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import com.example.batch.console.support.ConsoleExcelStyles;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleSingleSheetExcelImportSupport;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.support.FileTemplateExcelImportStore;
import com.example.batch.console.web.query.FileTemplateQueryRequest;
import com.example.batch.console.web.request.FileTemplateExcelApplyRequest;
import com.example.batch.console.web.response.ConsoleExcelRowIssueResponse;
import com.example.batch.console.web.response.ConsoleFileTemplateExcelApplyResponse;
import com.example.batch.console.web.response.ConsoleFileTemplateExcelPreviewResponse;
import com.example.batch.console.web.response.ConsoleFileTemplateExcelUploadResponse;
import com.example.batch.console.web.response.ConsoleFileTemplateResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@link com.example.batch.console.application.ConsoleFileTemplateExcelApplicationService} 的默认实现。
 */
@Service
@RequiredArgsConstructor
@SuppressWarnings("deprecation")
public class DefaultConsoleFileTemplateExcelApplicationService
    implements ConsoleFileTemplateExcelApplicationService {

  private static final String SHEET_NAME = "file_template_config";
  private static final List<String> COLUMNS =
      List.of(
          "tenant_id",
          "template_code",
          "template_name",
          "template_type",
          "biz_type",
          "file_format_type",
          "charset",
          "target_charset",
          "with_bom",
          "line_separator",
          "delimiter",
          "quote_char",
          "escape_char",
          "record_length",
          "header_rows",
          "footer_rows",
          "header_template",
          "trailer_template",
          "checksum_type",
          "compress_type",
          "encrypt_type",
          "naming_rule",
          "field_mappings",
          "validation_rule_set",
          "default_query_code",
          "default_query_sql",
          "query_param_schema",
          "streaming_enabled",
          "page_size",
          "fetch_size",
          "chunk_size",
          "preview_masking_enabled",
          "error_line_masking_enabled",
          "log_masking_enabled",
          "content_encryption_enabled",
          "encryption_key_ref",
          "download_requires_approval",
          "masking_rule_set",
          "enabled",
          "version",
          "description");
  private static final Set<String> REQUIRED_HEADERS = Set.copyOf(COLUMNS);
  private static final Set<String> FILE_FORMAT_TYPES = FileTemplateFormat.codes();
  private static final Set<String> TEMPLATE_TYPES = FileTemplateType.codes();
  private static final Set<String> CHECKSUM_TYPES = FileChecksumType.codes();
  private static final Set<String> COMPRESS_TYPES = FileCompressType.codes();
  private static final Set<String> ENCRYPT_TYPES = FileEncryptType.codes();
  private static final int[] BOOLEAN_VALIDATION_COLUMNS = {8, 27, 31, 32, 33, 34, 36, 38};
  private static final Map<String, ConsoleExcelStyles.ColumnGuide> COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry("tenant_id", optionalColumn("当前行所属租户。留空时，上传时自动使用当前租户。", "字符串", "tenant-a")),
          Map.entry(
              "template_code",
              requiredColumn("模板唯一编码，与 version 一起作为导入匹配键。", "字符串", "TPL_SETTLEMENT_001")),
          Map.entry("template_name", requiredColumn("控制台展示的模板名称。", "字符串", "清算导出模板")),
          Map.entry(
              "template_type",
              requiredColumn("文件模板在链路中的角色。", "枚举", "EXPORT", "IMPORT", "EXPORT", "SHARED")),
          Map.entry("biz_type", optionalColumn("用于检索和治理的业务分类标签。", "字符串", "SETTLEMENT")),
          Map.entry(
              "file_format_type",
              requiredColumn(
                  "模板生产或消费的物理文件格式。",
                  "枚举",
                  "DELIMITED",
                  "DELIMITED",
                  "FIXED_WIDTH",
                  "EXCEL",
                  "XML",
                  "JSON",
                  "BINARY")),
          Map.entry("charset", optionalColumn("文本类文件的源字符集。", "字符串", "UTF-8")),
          Map.entry("target_charset", optionalColumn("导出转换时的目标字符集。", "字符串", "GBK")),
          Map.entry("with_bom", optionalColumn("文本文件是否写入 BOM。", "布尔值", "FALSE", "TRUE", "FALSE")),
          Map.entry("line_separator", optionalColumn("文本输出使用的换行符。", "字符串", "\\n")),
          Map.entry("delimiter", optionalColumn("分隔文本使用的列分隔符。", "字符串", ",")),
          Map.entry("quote_char", optionalColumn("分隔文本使用的引号字符。", "字符串", "\"")),
          Map.entry("escape_char", optionalColumn("分隔文本使用的转义字符。", "字符串", "\\")),
          Map.entry("record_length", optionalColumn("定长文件的记录长度，必须大于等于 0。", "整数", "200")),
          Map.entry("header_rows", optionalColumn("文件头行数，必须大于等于 0。", "整数", "1")),
          Map.entry("footer_rows", optionalColumn("文件尾行数，必须大于等于 0。", "整数", "0")),
          Map.entry(
              "header_template",
              optionalColumn("文件头模板定义，请保持为合法 JSON。", "JSON", "{\"recordType\":\"H\"}")),
          Map.entry(
              "trailer_template",
              optionalColumn("文件尾模板定义，请保持为合法 JSON。", "JSON", "{\"recordType\":\"T\"}")),
          Map.entry(
              "checksum_type",
              requiredColumn("文件使用的校验算法。", "枚举", "NONE", "NONE", "MD5", "SHA-256")),
          Map.entry(
              "compress_type", requiredColumn("文件使用的压缩算法。", "枚举", "ZIP", "NONE", "ZIP", "GZIP")),
          Map.entry(
              "encrypt_type",
              requiredColumn("文件使用的加密算法。", "枚举", "NONE", "NONE", "AES", "PGP", "CUSTOM")),
          Map.entry("naming_rule", optionalColumn("文件命名规则或命名模板。", "表达式", "${bizDate}_${seq}.csv")),
          Map.entry(
              "field_mappings",
              optionalColumn(
                  "源字段与文件列之间的映射定义，请保持为合法 JSON。",
                  "JSON",
                  "[{\"source\":\"amount\",\"target\":\"AMOUNT\"}]")),
          Map.entry(
              "validation_rule_set",
              optionalColumn(
                  "解析或生成时执行的校验规则，请保持为合法 JSON。",
                  "JSON",
                  "[{\"field\":\"amount\",\"rule\":\"required\"}]")),
          Map.entry(
              "default_query_code",
              optionalColumn("导出步骤引用的默认查询编码。", "字符串", "QRY_SETTLEMENT_EXPORT")),
          Map.entry(
              "default_query_sql",
              optionalColumn("模板中可选的内联 SQL。", "SQL", "select * from settlement_result")),
          Map.entry(
              "query_param_schema",
              optionalColumn("查询参数 Schema，请保持为合法 JSON。", "JSON", "{\"type\":\"object\"}")),
          Map.entry(
              "streaming_enabled",
              optionalColumn("大结果集导出时是否启用流式处理。", "布尔值", "TRUE", "TRUE", "FALSE")),
          Map.entry("page_size", optionalColumn("分页读取大小，必须大于等于 0。", "整数", "1000")),
          Map.entry("fetch_size", optionalColumn("数据库抓取大小，必须大于等于 0。", "整数", "1000")),
          Map.entry("chunk_size", optionalColumn("分段生成文件时的块大小，必须大于等于 0。", "整数", "500")),
          Map.entry(
              "preview_masking_enabled",
              optionalColumn("预览结果是否脱敏。", "布尔值", "FALSE", "TRUE", "FALSE")),
          Map.entry(
              "error_line_masking_enabled",
              optionalColumn("错误明细导出是否脱敏。", "布尔值", "FALSE", "TRUE", "FALSE")),
          Map.entry(
              "log_masking_enabled",
              optionalColumn("日志是否对敏感字段脱敏。", "布尔值", "TRUE", "TRUE", "FALSE")),
          Map.entry(
              "content_encryption_enabled",
              optionalColumn("存储中的文件内容是否加密。", "布尔值", "FALSE", "TRUE", "FALSE")),
          Map.entry(
              "encryption_key_ref",
              optionalColumn("加密所使用的密钥引用。", "字符串", "kms://file-template/settlement")),
          Map.entry(
              "download_requires_approval",
              optionalColumn("下载前是否需要人工审批。", "布尔值", "TRUE", "TRUE", "FALSE")),
          Map.entry(
              "masking_rule_set", optionalColumn("脱敏规则集标识或表达式。", "字符串", "MASK_RULE_SETTLEMENT")),
          Map.entry("enabled", optionalColumn("文件模板是否启用。", "布尔值", "TRUE", "TRUE", "FALSE")),
          Map.entry("version", optionalColumn("模板版本号，template_code + version 必须唯一。", "整数", "1")),
          Map.entry("description", optionalColumn("面向运维人员的说明信息。", "字符串", "清算导出模板")));

  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final FileTemplateConfigMapper fileTemplateConfigMapper;
  private final ConfigChangeLogMapper configChangeLogMapper;
  private final FileTemplateExcelImportStore importStore;

  @Override
  public ResponseEntity<InputStreamResource> exportFileTemplates(FileTemplateQueryRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    List<Map<String, Object>> rows =
        fileTemplateConfigMapper.selectByQuery(
            tenantId,
            request.getKeyword(),
            request.getTemplateCode(),
            request.getTemplateName(),
            request.getTemplateType(),
            request.getBizType(),
            request.getEnabled(),
            null);
    byte[] workbookBytes = writeWorkbook(rows);
    InputStreamResource body = new InputStreamResource(new ByteArrayInputStream(workbookBytes));
    String fileName =
        "file-template-config-" + tenantId + "-" + Instant.now().toEpochMilli() + ".xlsx";
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(fileName).build().toString())
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(body);
  }

  @Override
  public ResponseEntity<InputStreamResource> downloadTemplate() {
    byte[] workbookBytes = writeWorkbook(List.of());
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename("file-template-config-template.xlsx")
                .build()
                .toString())
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(new InputStreamResource(new ByteArrayInputStream(workbookBytes)));
  }

  @Override
  public ConsoleFileTemplateExcelUploadResponse upload(MultipartFile file) throws IOException {
    Guard.require(file != null && !file.isEmpty(), "file is required");
    String tenantId = tenantGuard.resolveTenant(null);
    ConsoleSingleSheetExcelImportSupport.ParsedWorkbook parsedWorkbook =
        ConsoleSingleSheetExcelImportSupport.parseWorkbook(
            file.getBytes(),
            tenantId,
            file.getOriginalFilename(),
            "file-template-config.xlsx",
            COLUMNS,
            REQUIRED_HEADERS);
    String uploadToken =
        importStore.save(
            parsedWorkbook.fileName(),
            parsedWorkbook.tenantId(),
            parsedWorkbook.sheetName(),
            parsedWorkbook.rows());
    return new ConsoleFileTemplateExcelUploadResponse(
        uploadToken,
        parsedWorkbook.fileName(),
        parsedWorkbook.sheetName(),
        parsedWorkbook.rows().size());
  }

  @Override
  public ConsoleFileTemplateExcelPreviewResponse preview(String uploadToken) {
    ConsoleSingleSheetExcelImportSupport.ParsedSession session = loadSession(uploadToken);
    ValidationResult validationResult = validateRows(session);
    return new ConsoleFileTemplateExcelPreviewResponse(
        uploadToken,
        session.fileName(),
        session.sheetName(),
        validationResult.totalRows(),
        validationResult.validRows(),
        validationResult.invalidRows(),
        validationResult.rows().stream().map(this::toResponse).toList(),
        validationResult.issues());
  }

  @Override
  public ResponseEntity<InputStreamResource> downloadPreviewWorkbook(String uploadToken) {
    ConsoleSingleSheetExcelImportSupport.ParsedSession session = loadSession(uploadToken);
    ValidationResult validationResult = validateRows(session);
    byte[] workbookBytes = writePreviewWorkbook(session, validationResult);
    return ConsoleSingleSheetExcelImportSupport.excelResponse(
        ConsoleExcelPreviewWorkbookSupport.previewWorkbookFileName(session.fileName()),
        workbookBytes);
  }

  @Override
  @Transactional
  public ConsoleFileTemplateExcelApplyResponse apply(
      String uploadToken, FileTemplateExcelApplyRequest request) {
    ConsoleSingleSheetExcelImportSupport.ParsedSession session = loadSession(uploadToken);
    ValidationResult validationResult = validateRows(session);
    if (validationResult.invalidRows() > 0) {
      throw new BizException(ResultCode.INVALID_ARGUMENT, "excel contains invalid template rows");
    }
    ConsoleRequestMetadata metadata = requestMetadataResolver.current();
    String operatorId = metadata.operatorId();
    int inserted = 0;
    int updated = 0;
    for (TemplateRow row : validationResult.rows()) {
      Map<String, Object> existing =
          fileTemplateConfigMapper.selectByUniqueKey(
              session.tenantId(), row.templateCode(), row.version());
      fileTemplateConfigMapper.upsertFileTemplateConfig(
          toUpsertParam(session.tenantId(), row, operatorId));
      if (existing == null || existing.isEmpty()) {
        inserted++;
      } else {
        updated++;
      }
      logChange(
          session.tenantId(),
          row,
          request.getReason(),
          operatorId,
          metadata.traceId(),
          existing == null || existing.isEmpty() ? "EXCEL_INSERT" : "EXCEL_UPDATE");
    }
    importStore.remove(uploadToken);
    return new ConsoleFileTemplateExcelApplyResponse(
        uploadToken, session.tenantId(), validationResult.totalRows(), inserted, updated);
  }

  private ConsoleSingleSheetExcelImportSupport.ParsedSession loadSession(String uploadToken) {
    Guard.requireText(uploadToken, "uploadToken is required");
    return ConsoleSingleSheetExcelImportSupport.loadSession(
        uploadToken, importStore.get(uploadToken), tenantGuard);
  }

  private ValidationResult validateRows(
      ConsoleSingleSheetExcelImportSupport.ParsedSession session) {
    List<TemplateRow> rows = new ArrayList<>();
    List<ConsoleExcelRowIssueResponse> issues = new ArrayList<>();
    Set<String> uniqueKeys = new LinkedHashSet<>();
    int rowNo = 2;
    for (Map<String, String> rowValues : session.rows()) {
      List<String> rowIssues = new ArrayList<>();
      TemplateRow row = toTemplateRow(session.tenantId(), rowNo, rowValues, rowIssues);
      String uniqueKey = row.templateCode() + "#" + row.version();
      if (!uniqueKeys.add(uniqueKey)) {
        rowIssues.add("duplicate template code/version in excel: " + uniqueKey);
      }
      if (rowIssues.isEmpty()) {
        rows.add(row);
      } else {
        issues.add(
            new ConsoleExcelRowIssueResponse(
                rowNo, uniqueKey, row.templateCode(), row.version(), List.copyOf(rowIssues)));
      }
      rowNo++;
    }
    int totalRows = session.rows().size();
    return new ValidationResult(totalRows, rows.size(), totalRows - rows.size(), rows, issues);
  }

  private TemplateRow toTemplateRow(
      String tenantId, int rowNo, Map<String, String> values, List<String> issues) {
    String effectiveTenant = resolveTenantField(tenantId, values, issues);
    TemplateRow.TemplateRowBuilder builder =
        TemplateRow.builder().rowNo(rowNo).tenantId(effectiveTenant);
    extractBasicFields(builder, values, issues);
    extractFieldMappings(builder, values, issues);
    extractStepConfig(builder, values, issues);
    extractSecurityConfig(builder, values, issues);
    return builder.build();
  }

  private String resolveTenantField(
      String tenantId, Map<String, String> values, List<String> issues) {
    String effectiveTenant = normalize(values.get("tenant_id"));
    if (!StringUtils.hasText(effectiveTenant)) {
      return tenantId;
    }
    if (!tenantId.equals(effectiveTenant)) {
      issues.add("tenant_id must match current tenant: " + tenantId);
    }
    return effectiveTenant;
  }

  private void extractBasicFields(
      TemplateRow.TemplateRowBuilder builder, Map<String, String> values, List<String> issues) {
    builder
        .templateCode(requireText(values, "template_code", 128, issues))
        .templateName(requireText(values, "template_name", 256, issues))
        .templateType(requireEnum(values, "template_type", TEMPLATE_TYPES, 32, issues))
        .bizType(optionalText(values, "biz_type", 64, issues))
        .fileFormatType(requireEnum(values, "file_format_type", FILE_FORMAT_TYPES, 32, issues))
        .enabled(optionalBoolean(values, "enabled", true, issues))
        .version(optionalInteger(values, "version", 1, issues))
        .description(optionalText(values, "description", 1024, issues));
  }

  private void extractFieldMappings(
      TemplateRow.TemplateRowBuilder builder, Map<String, String> values, List<String> issues) {
    builder
        .charset(optionalText(values, "charset", 32, issues))
        .targetCharset(optionalText(values, "target_charset", 32, issues))
        .withBom(optionalBoolean(values, "with_bom", false, issues))
        .lineSeparator(optionalText(values, "line_separator", 16, issues))
        .delimiter(optionalText(values, "delimiter", 8, issues))
        .quoteChar(optionalText(values, "quote_char", 8, issues))
        .escapeChar(optionalText(values, "escape_char", 8, issues))
        .recordLength(optionalInteger(values, "record_length", 0, issues))
        .headerRows(optionalInteger(values, "header_rows", 0, issues))
        .footerRows(optionalInteger(values, "footer_rows", 0, issues))
        .headerTemplateJson(optionalJson(values, "header_template", issues))
        .trailerTemplateJson(optionalJson(values, "trailer_template", issues))
        .checksumType(requireEnum(values, "checksum_type", CHECKSUM_TYPES, 32, issues))
        .compressType(requireEnum(values, "compress_type", COMPRESS_TYPES, 32, issues))
        .encryptType(requireEnum(values, "encrypt_type", ENCRYPT_TYPES, 32, issues))
        .namingRule(optionalText(values, "naming_rule", 512, issues))
        .fieldMappingsJson(optionalJson(values, "field_mappings", issues))
        .validationRuleSetJson(optionalJson(values, "validation_rule_set", issues));
  }

  private void extractStepConfig(
      TemplateRow.TemplateRowBuilder builder, Map<String, String> values, List<String> issues) {
    builder
        .defaultQueryCode(optionalText(values, "default_query_code", 128, issues))
        .defaultQuerySql(optionalText(values, "default_query_sql", 10000, issues))
        .queryParamSchemaJson(optionalJson(values, "query_param_schema", issues))
        .streamingEnabled(optionalBoolean(values, "streaming_enabled", true, issues))
        .pageSize(optionalInteger(values, "page_size", 1000, issues))
        .fetchSize(optionalInteger(values, "fetch_size", 1000, issues))
        .chunkSize(optionalInteger(values, "chunk_size", 500, issues));
  }

  private void extractSecurityConfig(
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
            optionalBoolean(values, "download_requires_approval", false, issues))
        .maskingRuleSet(optionalText(values, "masking_rule_set", 256, issues));
  }

  private String requireText(
      Map<String, String> values, String key, int maxLength, List<String> issues) {
    String normalized = normalize(values.get(key));
    if (!StringUtils.hasText(normalized)) {
      issues.add(key + " is required");
      return null;
    }
    if (normalized.length() > maxLength) {
      issues.add(key + " too long (max " + maxLength + ")");
      return normalized.substring(0, maxLength);
    }
    return normalized;
  }

  private String optionalText(
      Map<String, String> values, String key, int maxLength, List<String> issues) {
    String normalized = normalize(values.get(key));
    if (!StringUtils.hasText(normalized)) {
      return null;
    }
    if (normalized.length() > maxLength) {
      issues.add(key + " too long (max " + maxLength + ")");
      return normalized.substring(0, maxLength);
    }
    return normalized;
  }

  private String requireEnum(
      Map<String, String> values,
      String key,
      Set<String> allowed,
      int maxLength,
      List<String> issues) {
    String normalized = requireText(values, key, maxLength, issues);
    if (normalized == null) {
      return null;
    }
    String upper = normalized.toUpperCase(Locale.ROOT);
    if (!allowed.contains(upper)) {
      issues.add(key + " must be one of " + allowed);
      return normalized;
    }
    return upper;
  }

  private Boolean optionalBoolean(
      Map<String, String> values, String key, Boolean defaultValue, List<String> issues) {
    String normalized = normalize(values.get(key));
    if (!StringUtils.hasText(normalized)) {
      return defaultValue;
    }
    if ("true".equalsIgnoreCase(normalized)
        || "1".equals(normalized)
        || "yes".equalsIgnoreCase(normalized)
        || "y".equalsIgnoreCase(normalized)) {
      return true;
    }
    if ("false".equalsIgnoreCase(normalized)
        || "0".equals(normalized)
        || "no".equalsIgnoreCase(normalized)
        || "n".equalsIgnoreCase(normalized)) {
      return false;
    }
    issues.add(key + " must be boolean");
    return defaultValue;
  }

  private Integer optionalInteger(
      Map<String, String> values, String key, Integer defaultValue, List<String> issues) {
    String normalized = normalize(values.get(key));
    if (!StringUtils.hasText(normalized)) {
      return defaultValue;
    }
    try {
      return Integer.valueOf(normalized);
    } catch (NumberFormatException exception) {
      issues.add(key + " must be integer");
      return defaultValue;
    }
  }

  private String optionalJson(Map<String, String> values, String key, List<String> issues) {
    String normalized = normalize(values.get(key));
    if (!StringUtils.hasText(normalized)) {
      return null;
    }
    try {
      JsonUtils.fromJson(normalized, Object.class);
      return normalized;
    } catch (IllegalArgumentException exception) {
      issues.add(key + " must be valid JSON");
      return normalized;
    }
  }

  private String normalize(String value) {
    return ConsoleTextSanitizer.normalize(value);
  }

  private byte[] writeWorkbook(List<Map<String, Object>> rows) {
    try (SXSSFWorkbook workbook = new SXSSFWorkbook(50);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet dataSheet = workbook.createSheet(SHEET_NAME);
      dataSheet.createFreezePane(0, 1);
      writeTemplateHeaders(dataSheet, COLUMNS, COLUMN_GUIDES, workbook);
      int rowIndex = 1;
      for (Map<String, Object> row : rows) {
        Row dataRow = dataSheet.createRow(rowIndex++);
        for (int i = 0; i < COLUMNS.size(); i++) {
          String header = COLUMNS.get(i);
          Cell cell = dataRow.createCell(i);
          Object value = row.get(header);
          if (value == null) {
            cell.setCellValue("");
          } else {
            cell.setCellValue(String.valueOf(value));
          }
        }
      }
      applyValidations(dataSheet);
      setWidths(dataSheet, COLUMNS);
      createReadmeSheet(workbook);
      createDictSheet(workbook);
      createValidationSheet(workbook);
      workbook.write(out);
      workbook.dispose();
      return out.toByteArray();
    } catch (IOException exception) {
      throw new BizException(ResultCode.SYSTEM_ERROR, "failed to generate excel workbook");
    }
  }

  private byte[] writePreviewWorkbook(
      ConsoleSingleSheetExcelImportSupport.ParsedSession session,
      ValidationResult validationResult) {
    List<WorkbookIssue> workbookIssues =
        validationResult.issues().stream()
            .flatMap(
                issue ->
                    ConsoleExcelPreviewWorkbookSupport.expandIssues(
                        SHEET_NAME, issue.rowNo(), issue.messages(), COLUMNS)
                        .stream())
            .toList();
    return ConsoleSingleSheetExcelImportSupport.writePreviewWorkbook(
        session,
        COLUMNS,
        COLUMN_GUIDES,
        this::applyValidations,
        workbook -> {
          createReadmeSheet(workbook);
          createDictSheet(workbook);
          createValidationSheet(workbook);
        },
        workbookIssues,
        1,
        "failed to generate preview excel workbook");
  }

  private void applyValidations(Sheet sheet) {
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

  private void createReadmeSheet(Workbook workbook) {
    Sheet sheet = workbook.createSheet("README");
    sheet.setColumnWidth(0, 16000);
    CellStyle titleStyle = createReadmeTitleStyle(workbook);
    String[] lines = {
      "file template config maintenance template",
      "1. Orange headers mark required fields. Hover the header to see field rules and"
          + " examples.",
      "2. template_code + version is the unique key used during preview and apply.",
      "3. Enum fields and boolean fields have built-in dropdown validation.",
      "4. JSON fields such as header_template, field_mappings, validation_rule_set, and"
          + " query_param_schema must stay valid JSON.",
      "5. Import flow is upload -> preview -> apply."
    };
    for (int i = 0; i < lines.length; i++) {
      Row row = sheet.createRow(i);
      row.createCell(0).setCellValue(lines[i]);
      if (i == 0) {
        row.getCell(0).setCellStyle(titleStyle);
      }
    }
  }

  private void createDictSheet(Workbook workbook) {
    Sheet sheet = workbook.createSheet("DICT");
    sheet.createFreezePane(0, 1);
    CellStyle dictHeaderStyle = ConsoleExcelStyles.createHeaderStyle(workbook);
    writeHeaders(sheet, List.of("field", "value", "description"), dictHeaderStyle);
    String[][] rows = {
      {"template_type", "IMPORT", "import template"},
      {"template_type", "EXPORT", "export template"},
      {"template_type", "SHARED", "shared template"},
      {"file_format_type", "DELIMITED", "delimited text"},
      {"file_format_type", "FIXED_WIDTH", "fixed width text"},
      {"file_format_type", "EXCEL", "excel workbook"},
      {"file_format_type", "XML", "xml payload"},
      {"file_format_type", "JSON", "json payload"},
      {"file_format_type", "BINARY", "binary payload"},
      {"checksum_type", "NONE", "no checksum"},
      {"checksum_type", "MD5", "md5 checksum"},
      {"checksum_type", "SHA-256", "sha-256 checksum"},
      {"compress_type", "NONE", "no compression"},
      {"compress_type", "ZIP", "zip compression"},
      {"compress_type", "GZIP", "gzip compression"},
      {"encrypt_type", "NONE", "no encryption"},
      {"encrypt_type", "AES", "aes encryption"},
      {"encrypt_type", "PGP", "pgp encryption"},
      {"encrypt_type", "CUSTOM", "custom encryption"},
      {"enabled", "TRUE", "enabled"},
      {"enabled", "FALSE", "disabled"},
      {"with_bom", "TRUE", "with bom"},
      {"with_bom", "FALSE", "without bom"},
      {"streaming_enabled", "TRUE", "streaming"},
      {"streaming_enabled", "FALSE", "non-streaming"},
      {"download_requires_approval", "TRUE", "requires approval"},
      {"download_requires_approval", "FALSE", "no approval"}
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

  private void createValidationSheet(Workbook workbook) {
    ConsoleExcelStyles.createValidationSheet(workbook);
  }

  private void logChange(
      String tenantId,
      TemplateRow row,
      String reason,
      String operatorId,
      String traceId,
      String action) {
    configChangeLogMapper.insertConfigChangeLog(
        mapOf(
            "tenantId",
            tenantId,
            "configType",
            "FILE_TEMPLATE",
            "configKey",
            row.templateCode(),
            "versionNo",
            row.version(),
            "changeAction",
            action,
            "changeResult",
            "SUCCESS",
            "operatorType",
            "USER",
            "operatorId",
            ConsoleTextSanitizer.safeInput(operatorId, 64),
            "traceId",
            ConsoleTextSanitizer.safeInput(traceId, 128),
            "changeSummaryJson",
            JsonUtils.toJson(
                mapOf(
                    "reason", ConsoleTextSanitizer.safeInput(reason, 512),
                    "detail",
                        mapOf(
                            "templateName", row.templateName(),
                            "enabled", row.enabled(),
                            "fileFormatType", row.fileFormatType())))));
  }

  private Map<String, Object> mapOf(Object... pairs) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      values.put(String.valueOf(pairs[index]), pairs[index + 1]);
    }
    return values;
  }

  private ConsoleFileTemplateResponse toResponse(TemplateRow row) {
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

  private FileTemplateConfigUpsertParam toUpsertParam(
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

  private record ValidationResult(
      int totalRows,
      int validRows,
      int invalidRows,
      List<TemplateRow> rows,
      List<ConsoleExcelRowIssueResponse> issues) {}

  @Getter
  @Builder
  @Accessors(fluent = true)
  private static class TemplateRow {
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
