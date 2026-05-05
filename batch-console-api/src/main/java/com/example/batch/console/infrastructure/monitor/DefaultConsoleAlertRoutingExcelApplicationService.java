package com.example.batch.console.infrastructure.monitor;

import static com.example.batch.console.support.excel.ConsoleExcelStyles.addBooleanValidation;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.addDropdownValidation;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.createReadmeTitleStyle;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.optionalColumn;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.requiredColumn;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.setGuideColumnWidths;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.setReadmeColumnWidth;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.writeHeaders;

import com.example.batch.common.enums.AlertSeverity;
import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.console.application.monitor.ConsoleAlertRoutingExcelApplicationService;
import com.example.batch.console.domain.param.AlertRoutingConfigUpsertParam;
import com.example.batch.console.infrastructure.excel.AbstractSingleSheetExcelService;
import com.example.batch.console.mapper.AlertRoutingConfigMapper;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.support.ConfigChangeLogBuilder;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.support.excel.ConsoleExcelStyles;
import com.example.batch.console.support.excel.ConsoleExcelStyles.ColumnGuide;
import com.example.batch.console.support.excel.ExcelImportStore;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.query.AlertRoutingQueryRequest;
import com.example.batch.console.web.request.excel.ExcelApplyRequest;
import com.example.batch.console.web.response.config.ConsoleAlertRoutingResponse;
import com.example.batch.console.web.response.excel.ExcelApplyResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Builder;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link ConsoleAlertRoutingExcelApplicationService} 的默认实现。 */
@Service
public class DefaultConsoleAlertRoutingExcelApplicationService
    extends AbstractSingleSheetExcelService<
        DefaultConsoleAlertRoutingExcelApplicationService.RoutingRow, ConsoleAlertRoutingResponse>
    implements ConsoleAlertRoutingExcelApplicationService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String COL_TEAM = "team";
  private static final String COL_RECEIVER = "receiver";
  private static final String COL_DESCRIPTION = "description";
  private static final String GUIDE_TRUE = "TRUE";
  private static final String COL_SEVERITY = "severity";
  private static final String GUIDE_STR = "字符串";
  private static final String COL_ENABLED = "enabled";

  private static final String SHEET_NAME = "alert_routing_config";
  private static final List<String> COLUMNS =
      List.of(
          "tenant_id",
          "route_code",
          "route_name",
          COL_TEAM,
          "alert_group",
          COL_SEVERITY,
          COL_RECEIVER,
          "group_by",
          "group_wait_seconds",
          "group_interval_seconds",
          "repeat_interval_seconds",
          COL_ENABLED,
          COL_DESCRIPTION);
  private static final Set<String> SEVERITIES = DictEnum.codes(AlertSeverity.class);
  private static final Map<String, ColumnGuide> COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry("tenant_id", optionalColumn("当前行所属租户。留空时，上传时自动使用当前租户。", GUIDE_STR, "tenant-a")),
          Map.entry("route_code", requiredColumn("路由唯一编码，作为导入匹配键。", GUIDE_STR, "RT_BATCH_ERROR")),
          Map.entry("route_name", requiredColumn("控制台展示的路由名称。", GUIDE_STR, "批处理异常路由")),
          Map.entry(COL_TEAM, requiredColumn("负责该路由的团队或值班组。", GUIDE_STR, "ops")),
          Map.entry("alert_group", requiredColumn("通知引擎使用的告警分组。", GUIDE_STR, "batch")),
          Map.entry(
              COL_SEVERITY,
              requiredColumn("该路由处理的告警级别。", "枚举", "ERROR", "INFO", "WARN", "ERROR", "CRITICAL")),
          Map.entry(COL_RECEIVER, requiredColumn("目标接收方、通道或 webhook 别名。", GUIDE_STR, "slack-ops")),
          Map.entry("group_by", optionalColumn("用于去重和聚合的分组键，可选。", "表达式", "job_code")),
          Map.entry("group_wait_seconds", optionalColumn("首次聚合通知前的等待秒数，必须大于等于 0。", "整数", "30")),
          Map.entry(
              "group_interval_seconds", optionalColumn("两次聚合通知之间的最小间隔，必须大于等于 0。", "整数", "300")),
          Map.entry(
              "repeat_interval_seconds", optionalColumn("持续告警的重复通知间隔，必须大于等于 0。", "整数", "3600")),
          Map.entry(
              COL_ENABLED, optionalColumn("告警路由是否启用。", "布尔值", GUIDE_TRUE, GUIDE_TRUE, "FALSE")),
          Map.entry(COL_DESCRIPTION, optionalColumn("面向运维人员的说明信息。", GUIDE_STR, "批处理失败默认路由")));

  private final AlertRoutingConfigMapper alertRoutingConfigMapper;
  private final ConfigChangeLogMapper configChangeLogMapper;

  public DefaultConsoleAlertRoutingExcelApplicationService(
      ConsoleTenantGuard tenantGuard,
      ConsoleRequestMetadataResolver requestMetadataResolver,
      ExcelImportStore importStore,
      BatchDateTimeSupport dateTimeSupport,
      AlertRoutingConfigMapper alertRoutingConfigMapper,
      ConfigChangeLogMapper configChangeLogMapper) {
    super(tenantGuard, requestMetadataResolver, importStore, dateTimeSupport);
    this.alertRoutingConfigMapper = alertRoutingConfigMapper;
    this.configChangeLogMapper = configChangeLogMapper;
  }

  @Override
  public ResponseEntity<InputStreamResource> exportAlertRoutings(AlertRoutingQueryRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    List<Map<String, Object>> rows =
        alertRoutingConfigMapper.selectByQuery(
            tenantId,
            request.getRouteCode(),
            request.getTeam(),
            request.getSeverity(),
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
  protected RoutingRow parseRow(
      String tenantId, int rowNo, Map<String, String> values, List<String> issues) {
    String effectiveTenant = resolveTenantField(values, tenantId, issues);
    return RoutingRow.builder()
        .rowNo(rowNo)
        .tenantId(effectiveTenant)
        .routeCode(requireText(values, "route_code", 128, issues))
        .routeName(requireText(values, "route_name", 256, issues))
        .team(requireText(values, COL_TEAM, 128, issues))
        .alertGroup(requireText(values, "alert_group", 128, issues))
        .severity(requireEnum(values, COL_SEVERITY, SEVERITIES, 16, issues))
        .receiver(requireText(values, COL_RECEIVER, 256, issues))
        .groupBy(optionalText(values, "group_by", 512, issues))
        .groupWaitSeconds(optionalInteger(values, "group_wait_seconds", 0, 30, issues))
        .groupIntervalSeconds(optionalInteger(values, "group_interval_seconds", 0, 300, issues))
        .repeatIntervalSeconds(optionalInteger(values, "repeat_interval_seconds", 0, 3600, issues))
        .enabled(optionalBoolean(values, COL_ENABLED, true, issues))
        .description(optionalText(values, COL_DESCRIPTION, 1024, issues))
        .build();
  }

  @Override
  protected String rowUniqueKey(RoutingRow row) {
    return row.routeCode();
  }

  @Override
  protected ConsoleAlertRoutingResponse toResponse(RoutingRow row) {
    return new ConsoleAlertRoutingResponse(
        null,
        row.tenantId(),
        row.routeCode(),
        row.routeName(),
        row.team(),
        row.alertGroup(),
        row.severity(),
        row.receiver(),
        row.groupBy(),
        row.groupWaitSeconds(),
        row.groupIntervalSeconds(),
        row.repeatIntervalSeconds(),
        row.enabled(),
        row.description(),
        null,
        null);
  }

  @Override
  protected boolean upsertRow(RoutingRow row, String tenantId, String operatorId) {
    Map<String, Object> existing =
        alertRoutingConfigMapper.selectByUniqueKey(tenantId, row.routeCode());
    AlertRoutingConfigUpsertParam param = new AlertRoutingConfigUpsertParam();
    param.setTenantId(tenantId);
    param.setRouteCode(row.routeCode());
    param.setRouteName(row.routeName());
    param.setTeam(row.team());
    param.setAlertGroup(row.alertGroup());
    param.setSeverity(row.severity());
    param.setReceiver(row.receiver());
    param.setGroupBy(row.groupBy());
    param.setGroupWaitSeconds(row.groupWaitSeconds());
    param.setGroupIntervalSeconds(row.groupIntervalSeconds());
    param.setRepeatIntervalSeconds(row.repeatIntervalSeconds());
    param.setEnabled(row.enabled());
    param.setDescription(row.description());
    param.setCreatedBy(ConsoleTextSanitizer.safeInput(operatorId, 64));
    param.setUpdatedBy(ConsoleTextSanitizer.safeInput(operatorId, 64));
    alertRoutingConfigMapper.upsertAlertRoutingConfig(param);
    return existing == null || existing.isEmpty();
  }

  @Override
  protected boolean rowExists(RoutingRow row, String tenantId) {
    Map<String, Object> existing =
        alertRoutingConfigMapper.selectByUniqueKey(tenantId, row.routeCode());
    return existing != null && !existing.isEmpty();
  }

  @Override
  protected void logChange(
      String tenantId,
      RoutingRow row,
      String reason,
      String operatorId,
      String traceId,
      String action) {
    configChangeLogMapper.insertConfigChangeLog(
        ConfigChangeLogBuilder.create(tenantId, operatorId, traceId)
            .forType("ALERT_ROUTING")
            .withKey(row.routeCode())
            .action(action)
            .summary(
                changeSummaryJson(
                    reason,
                    mapOf(
                        "routeName",
                        row.routeName(),
                        COL_TEAM,
                        row.team(),
                        COL_SEVERITY,
                        row.severity(),
                        COL_RECEIVER,
                        row.receiver())))
            .build());
  }

  @Override
  protected void applyValidations(Sheet sheet) {
    addDropdownValidation(
        sheet, 5, SEVERITIES.toArray(String[]::new), "severity 填写提示", "请从下拉列表中选择告警级别。");
    addBooleanValidation(sheet, new int[] {11}, "enabled 填写提示", "请填写 TRUE 或 FALSE。");
  }

  @Override
  protected void createReadmeSheet(Workbook workbook) {
    Sheet sheet = workbook.createSheet(ConsoleExcelStyles.SHEET_NAME_README);
    setReadmeColumnWidth(sheet);
    CellStyle titleStyle = createReadmeTitleStyle(workbook);
    String[] lines = {
      "告警路由配置维护模板",
      "1. 橙色表头表示必填字段；鼠标悬停表头可查看字段规则与示例。",
      "2. severity 与 enabled 已内置下拉值校验。",
      "3. route_code 是预览与应用阶段使用的唯一键。",
      "4. 时间字段以秒为单位的整数表示,必须 ≥ 0。",
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
      {COL_SEVERITY, "INFO", "informational"},
      {COL_SEVERITY, "WARN", "warning"},
      {COL_SEVERITY, "ERROR", "error"},
      {COL_SEVERITY, "CRITICAL", "critical"},
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

  @Builder
  record RoutingRow(
      int rowNo,
      String tenantId,
      String routeCode,
      String routeName,
      String team,
      String alertGroup,
      String severity,
      String receiver,
      String groupBy,
      Integer groupWaitSeconds,
      Integer groupIntervalSeconds,
      Integer repeatIntervalSeconds,
      Boolean enabled,
      String description) {}
}
