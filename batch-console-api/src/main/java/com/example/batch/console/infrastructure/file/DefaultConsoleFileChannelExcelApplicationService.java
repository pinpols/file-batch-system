package com.example.batch.console.infrastructure.file;

import static com.example.batch.console.support.excel.ConsoleExcelStyles.addDropdownValidation;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.createReadmeTitleStyle;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.optionalColumn;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.requiredColumn;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.setGuideColumnWidths;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.setReadmeColumnWidth;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.writeHeaders;

import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.FileChannelAuthType;
import com.example.batch.common.enums.FileChannelType;
import com.example.batch.common.enums.FileReceiptPolicy;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.console.application.file.ConsoleFileChannelExcelApplicationService;
import com.example.batch.console.domain.param.FileChannelConfigUpsertParam;
import com.example.batch.console.infrastructure.excel.AbstractSingleSheetExcelService;
import com.example.batch.console.infrastructure.excel.ConfigPackageExcelSchema;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.FileChannelConfigMapper;
import com.example.batch.console.support.ConfigChangeLogBuilder;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.support.excel.ConsoleExcelStyles;
import com.example.batch.console.support.excel.ConsoleExcelStyles.ColumnGuide;
import com.example.batch.console.support.excel.ExcelImportStore;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.query.FileChannelQueryRequest;
import com.example.batch.console.web.request.excel.ExcelApplyRequest;
import com.example.batch.console.web.response.excel.ExcelApplyResponse;
import com.example.batch.console.web.response.file.ConsoleFileChannelResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.Builder;
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

/** {@link ConsoleFileChannelExcelApplicationService} 的默认实现。 */
@Service
public class DefaultConsoleFileChannelExcelApplicationService
    extends AbstractSingleSheetExcelService<
        DefaultConsoleFileChannelExcelApplicationService.ChannelRow, ConsoleFileChannelResponse>
    implements ConsoleFileChannelExcelApplicationService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String GUIDE_NONE = "NONE";
  private static final String GUIDE_TRUE = "TRUE";
  private static final String COL_CHANNEL_TYPE = "channel_type";
  private static final String COL_AUTH_TYPE = "auth_type";
  private static final String COL_RECEIPT_POLICY = "receipt_policy";
  private static final String COL_ENABLED = "enabled";
  private static final String COL_DESCRIPTION = "description";
  private static final String GUIDE_STR = "字符串";

  private static final String SHEET_NAME = "file_channel_config";
  private static final List<String> COLUMNS = ConfigPackageExcelSchema.FileChannel.COLUMNS;
  private static final Set<String> CHANNEL_TYPES = DictEnum.codes(FileChannelType.class);
  private static final Set<String> AUTH_TYPES = DictEnum.codes(FileChannelAuthType.class);
  private static final Set<String> RECEIPT_POLICIES = DictEnum.codes(FileReceiptPolicy.class);
  private static final Map<String, ColumnGuide> COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry(
              "tenant_id",
              optionalColumn(
                  "excel.channel.tenant_id.desc", "excel.guide.format.string", "tenant-a")),
          Map.entry(
              "channel_code",
              requiredColumn(
                  "excel.channel.channel_code.desc",
                  "excel.guide.format.string",
                  "CH_API_SETTLEMENT")),
          Map.entry(
              "channel_name",
              requiredColumn(
                  "excel.channel.channel_name.desc", "excel.guide.format.string", "清算 API 通道")),
          Map.entry(
              COL_CHANNEL_TYPE,
              requiredColumn(
                  "excel.channel.channel_type.desc",
                  "excel.guide.format.enum",
                  "API",
                  "SFTP",
                  "API",
                  "EMAIL",
                  "NAS",
                  "OSS",
                  "LOCAL")),
          Map.entry(
              "target_endpoint",
              optionalColumn(
                  "excel.channel.target_endpoint.desc",
                  "excel.guide.format.endpoint",
                  "https://api.example.com/push")),
          Map.entry(
              COL_AUTH_TYPE,
              requiredColumn(
                  "excel.channel.auth_type.desc",
                  "excel.guide.format.enum",
                  "TOKEN",
                  GUIDE_NONE,
                  "PASSWORD",
                  "KEY_PAIR",
                  "TOKEN",
                  "OAUTH2",
                  "CUSTOM")),
          Map.entry(
              "config_json",
              requiredColumn(
                  "excel.channel.config_json.desc",
                  "excel.guide.format.json",
                  "{\"token\":\"xxx\"}")),
          Map.entry(
              COL_RECEIPT_POLICY,
              requiredColumn(
                  "excel.channel.receipt_policy.desc",
                  "excel.guide.format.enum",
                  "SYNC",
                  GUIDE_NONE,
                  "SYNC",
                  "ASYNC",
                  "POLLING")),
          Map.entry(
              "timeout_seconds",
              requiredColumn(
                  "excel.channel.timeout_seconds.desc", "excel.guide.format.integer", "30")),
          Map.entry(
              COL_ENABLED,
              optionalColumn(
                  "excel.channel.enabled.desc",
                  "excel.guide.format.boolean",
                  GUIDE_TRUE,
                  GUIDE_TRUE,
                  "FALSE")));

  private final FileChannelConfigMapper fileChannelConfigMapper;
  private final ConfigChangeLogMapper configChangeLogMapper;

  public DefaultConsoleFileChannelExcelApplicationService(
      ConsoleTenantGuard tenantGuard,
      ConsoleRequestMetadataResolver requestMetadataResolver,
      ExcelImportStore importStore,
      BatchDateTimeSupport dateTimeSupport,
      MessageSource messageSource,
      FileChannelConfigMapper fileChannelConfigMapper,
      ConfigChangeLogMapper configChangeLogMapper) {
    super(tenantGuard, requestMetadataResolver, importStore, dateTimeSupport, messageSource);
    this.fileChannelConfigMapper = fileChannelConfigMapper;
    this.configChangeLogMapper = configChangeLogMapper;
  }

  @Override
  public ResponseEntity<InputStreamResource> exportFileChannels(FileChannelQueryRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    List<Map<String, Object>> rows =
        fileChannelConfigMapper.selectByQuery(
            tenantId,
            request.getChannelCode(),
            request.getChannelType(),
            request.getEnabled(),
            null);
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
  protected ChannelRow parseRow(
      String tenantId, int rowNo, Map<String, String> values, List<String> issues) {
    String effectiveTenant = resolveTenantField(values, tenantId, issues);
    return ChannelRow.builder()
        .rowNo(rowNo)
        .tenantId(effectiveTenant)
        .channelCode(requireText(values, "channel_code", 128, issues))
        .channelName(requireText(values, "channel_name", 256, issues))
        .channelType(requireEnum(values, COL_CHANNEL_TYPE, CHANNEL_TYPES, 32, issues))
        .targetEndpoint(optionalText(values, "target_endpoint", 1024, issues))
        .authType(requireEnum(values, COL_AUTH_TYPE, AUTH_TYPES, 32, issues))
        .configJson(requireJson(values, "config_json", issues))
        .receiptPolicy(requireEnum(values, COL_RECEIPT_POLICY, RECEIPT_POLICIES, 32, issues))
        .timeoutSeconds(requireInteger(values, "timeout_seconds", 0, issues))
        .enabled(optionalBoolean(values, COL_ENABLED, true, issues))
        .build();
  }

  @Override
  protected String rowUniqueKey(ChannelRow row) {
    return row.channelCode();
  }

  @Override
  protected ConsoleFileChannelResponse toResponse(ChannelRow row) {
    return new ConsoleFileChannelResponse(
        null,
        row.tenantId(),
        row.channelCode(),
        row.channelName(),
        row.channelType(),
        row.targetEndpoint(),
        row.authType(),
        row.configJson(),
        row.receiptPolicy(),
        row.timeoutSeconds(),
        row.enabled(),
        null,
        null);
  }

  @Override
  protected boolean upsertRow(ChannelRow row, String tenantId, String operatorId) {
    Map<String, Object> existing =
        fileChannelConfigMapper.selectByUniqueKey(tenantId, row.channelCode());
    FileChannelConfigUpsertParam param = new FileChannelConfigUpsertParam();
    param.setTenantId(tenantId);
    param.setChannelCode(row.channelCode());
    param.setChannelName(row.channelName());
    param.setChannelType(row.channelType());
    param.setTargetEndpoint(row.targetEndpoint());
    param.setAuthType(row.authType());
    param.setConfigJson(row.configJson());
    param.setReceiptPolicy(row.receiptPolicy());
    param.setTimeoutSeconds(row.timeoutSeconds());
    param.setEnabled(row.enabled());
    param.setCreatedBy(ConsoleTextSanitizer.safeInput(operatorId, 64));
    param.setUpdatedBy(ConsoleTextSanitizer.safeInput(operatorId, 64));
    fileChannelConfigMapper.upsertFileChannelConfig(param);
    return existing == null || existing.isEmpty();
  }

  @Override
  protected boolean rowExists(ChannelRow row, String tenantId) {
    Map<String, Object> existing =
        fileChannelConfigMapper.selectByUniqueKey(tenantId, row.channelCode());
    return existing != null && !existing.isEmpty();
  }

  @Override
  protected void logChange(
      String tenantId,
      ChannelRow row,
      String reason,
      String operatorId,
      String traceId,
      String action) {
    configChangeLogMapper.insertConfigChangeLog(
        ConfigChangeLogBuilder.create(tenantId, operatorId, traceId)
            .forType("FILE_CHANNEL")
            .withKey(row.channelCode())
            .action(action)
            .summary(
                changeSummaryJson(
                    reason,
                    mapOf(
                        "channelName",
                        row.channelName(),
                        "channelType",
                        row.channelType(),
                        "authType",
                        row.authType(),
                        "receiptPolicy",
                        row.receiptPolicy(),
                        "timeoutSeconds",
                        row.timeoutSeconds())))
            .build());
  }

  @Override
  protected void applyValidations(Sheet sheet) {
    Locale locale = LocaleContextHolder.getLocale();
    addDropdownValidation(
        sheet,
        3,
        CHANNEL_TYPES.toArray(String[]::new),
        "excel.channel.channel_type.prompt_title",
        "excel.channel.channel_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        5,
        AUTH_TYPES.toArray(String[]::new),
        "excel.channel.auth_type.prompt_title",
        "excel.channel.auth_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        7,
        RECEIPT_POLICIES.toArray(String[]::new),
        "excel.channel.receipt_policy.prompt_title",
        "excel.channel.receipt_policy.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        9,
        new String[] {"TRUE", "FALSE"},
        "excel.common.enabled.prompt_title",
        "excel.common.enabled.prompt_box",
        messageSource,
        locale);
  }

  @Override
  protected void createReadmeSheet(Workbook workbook) {
    Locale locale = LocaleContextHolder.getLocale();
    Sheet sheet = workbook.createSheet(ConsoleExcelStyles.SHEET_NAME_README);
    setReadmeColumnWidth(sheet);
    CellStyle titleStyle = createReadmeTitleStyle(workbook);
    String[] keys = {
      "excel.channel.readme.title",
      "excel.channel.readme.line1",
      "excel.channel.readme.line2",
      "excel.channel.readme.line3",
      "excel.channel.readme.line4",
      "excel.channel.readme.line5"
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
      {COL_CHANNEL_TYPE, "SFTP", "sftp channel"},
      {COL_CHANNEL_TYPE, "API", "api channel"},
      {COL_CHANNEL_TYPE, "EMAIL", "email channel"},
      {COL_CHANNEL_TYPE, "NAS", "nas channel"},
      {COL_CHANNEL_TYPE, "OSS", "object storage"},
      {COL_CHANNEL_TYPE, "LOCAL", "local filesystem"},
      {COL_AUTH_TYPE, GUIDE_NONE, "no auth"},
      {COL_AUTH_TYPE, "PASSWORD", "password auth"},
      {COL_AUTH_TYPE, "KEY_PAIR", "key pair auth"},
      {COL_AUTH_TYPE, "TOKEN", "token auth"},
      {COL_AUTH_TYPE, "OAUTH2", "oauth2 auth"},
      {COL_AUTH_TYPE, "CUSTOM", "custom auth"},
      {COL_RECEIPT_POLICY, GUIDE_NONE, "no receipt"},
      {COL_RECEIPT_POLICY, "SYNC", "synchronous receipt"},
      {COL_RECEIPT_POLICY, "ASYNC", "asynchronous receipt"},
      {COL_RECEIPT_POLICY, "POLLING", "polling receipt"},
      {COL_ENABLED, GUIDE_TRUE, COL_ENABLED},
      {COL_ENABLED, "FALSE", "disabled"}
    };
    for (int i = 0; i < rows.length; i++) {
      Row row = sheet.createRow(i + 1);
      row.createCell(0).setCellValue(rows[i][0]);
      row.createCell(1).setCellValue(rows[i][1]);
      row.createCell(2).setCellValue(rows[i][2]);
    }
    setGuideColumnWidths(sheet);
  }

  private static String requireJson(Map<String, String> values, String key, List<String> issues) {
    String normalized = normalize(values.get(key));
    if (!Texts.hasText(normalized)) {
      issues.add(key + " is required");
      return null;
    }
    try {
      JsonUtils.fromJson(normalized, Object.class);
      return normalized;
    } catch (IllegalArgumentException exception) {
      SwallowedExceptionLogger.info(
          DefaultConsoleFileChannelExcelApplicationService.class,
          "catch:IllegalArgumentException",
          exception);

      issues.add(key + " must be valid JSON");
      return normalized;
    }
  }

  @Builder
  record ChannelRow(
      int rowNo,
      String tenantId,
      String channelCode,
      String channelName,
      String channelType,
      String targetEndpoint,
      String authType,
      String configJson,
      String receiptPolicy,
      Integer timeoutSeconds,
      Boolean enabled) {}
}
