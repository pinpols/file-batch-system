package com.example.batch.console.infrastructure;

import static com.example.batch.console.support.ConsoleExcelStyles.addBooleanValidation;
import static com.example.batch.console.support.ConsoleExcelStyles.createReadmeTitleStyle;
import static com.example.batch.console.support.ConsoleExcelStyles.optionalColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.requiredColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.writeHeaders;

import com.example.batch.console.application.ConsoleTenantQuotaPolicyExcelApplicationService;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.TenantQuotaPolicyMapper;
import com.example.batch.console.mapper.param.TenantQuotaPolicyUpsertParam;
import com.example.batch.console.support.ConfigChangeLogBuilder;
import com.example.batch.console.support.ConsoleExcelStyles;
import com.example.batch.console.support.ConsoleExcelStyles.ColumnGuide;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.support.ExcelImportStore;
import com.example.batch.console.web.request.ExcelApplyRequest;
import com.example.batch.console.web.response.ConsoleTenantQuotaPolicyResponse;
import com.example.batch.console.web.response.ExcelApplyResponse;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link ConsoleTenantQuotaPolicyExcelApplicationService} 的默认实现。 */
@Service
public class DefaultConsoleTenantQuotaPolicyExcelApplicationService
    extends AbstractSingleSheetExcelService<
        DefaultConsoleTenantQuotaPolicyExcelApplicationService.PolicyRow,
        ConsoleTenantQuotaPolicyResponse>
    implements ConsoleTenantQuotaPolicyExcelApplicationService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String COL_DESCRIPTION = "description";
  private static final String GUIDE_TRUE = "TRUE";
  private static final String COL_ENABLED = "enabled";
  private static final String GUIDE_STR = "字符串";

  private static final String SHEET_NAME = "tenant_quota_policy";
  private static final List<String> COLUMNS =
      List.of(
          "tenant_id",
          "policy_code",
          "max_running_jobs_per_tenant",
          "max_partitions_per_tenant",
          "max_qps_per_tenant",
          "fair_share_weight",
          COL_ENABLED,
          COL_DESCRIPTION);
  private static final Map<String, ColumnGuide> COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry("tenant_id", optionalColumn("当前行所属租户。留空时，上传时自动使用当前租户。", GUIDE_STR, "tenant-a")),
          Map.entry("policy_code", requiredColumn("策略唯一编码，作为导入匹配键。", GUIDE_STR, "DEFAULT_POLICY")),
          Map.entry(
              "max_running_jobs_per_tenant", requiredColumn("租户最大并行作业数，必须 >= 0。", "整数", "10")),
          Map.entry("max_partitions_per_tenant", requiredColumn("租户最大分区数，必须 >= 0。", "整数", "100")),
          Map.entry("max_qps_per_tenant", requiredColumn("租户最大 QPS，必须 >= 0。", "整数", "50")),
          Map.entry("fair_share_weight", requiredColumn("公平调度权重，必须 >= 1。", "整数", "1")),
          Map.entry(
              COL_ENABLED,
              optionalColumn("策略是否启用，默认 TRUE。", "布尔值", GUIDE_TRUE, GUIDE_TRUE, "FALSE")),
          Map.entry(COL_DESCRIPTION, optionalColumn("策略描述信息。", GUIDE_STR, "默认配额策略")));

  private final TenantQuotaPolicyMapper tenantQuotaPolicyMapper;
  private final ConfigChangeLogMapper configChangeLogMapper;

  public DefaultConsoleTenantQuotaPolicyExcelApplicationService(
      ConsoleTenantGuard tenantGuard,
      ConsoleRequestMetadataResolver requestMetadataResolver,
      ExcelImportStore importStore,
      TenantQuotaPolicyMapper tenantQuotaPolicyMapper,
      ConfigChangeLogMapper configChangeLogMapper) {
    super(tenantGuard, requestMetadataResolver, importStore);
    this.tenantQuotaPolicyMapper = tenantQuotaPolicyMapper;
    this.configChangeLogMapper = configChangeLogMapper;
  }

  @Override
  public ResponseEntity<InputStreamResource> exportQuotaPolicies(
      String tenantId, String policyCode, Boolean enabled) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    List<Map<String, Object>> rows =
        tenantQuotaPolicyMapper.selectByQuery(resolvedTenantId, policyCode, enabled, null);
    return doExport(resolvedTenantId, rows);
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
  protected PolicyRow parseRow(
      String tenantId, int rowNo, Map<String, String> values, List<String> issues) {
    String effectiveTenant = resolveTenantField(values, tenantId, issues);
    return PolicyRow.builder()
        .rowNo(rowNo)
        .tenantId(effectiveTenant)
        .policyCode(requireText(values, "policy_code", 128, issues))
        .maxRunningJobsPerTenant(requireInteger(values, "max_running_jobs_per_tenant", 0, issues))
        .maxPartitionsPerTenant(requireInteger(values, "max_partitions_per_tenant", 0, issues))
        .maxQpsPerTenant(requireInteger(values, "max_qps_per_tenant", 0, issues))
        .fairShareWeight(requireInteger(values, "fair_share_weight", 1, issues))
        .enabled(optionalBoolean(values, COL_ENABLED, true, issues))
        .description(optionalText(values, COL_DESCRIPTION, 512, issues))
        .build();
  }

  @Override
  protected String rowUniqueKey(PolicyRow row) {
    return row.policyCode();
  }

  @Override
  protected ConsoleTenantQuotaPolicyResponse toResponse(PolicyRow row) {
    return new ConsoleTenantQuotaPolicyResponse(
        null,
        row.tenantId(),
        row.policyCode(),
        row.maxRunningJobsPerTenant(),
        row.maxPartitionsPerTenant(),
        row.maxQpsPerTenant(),
        row.fairShareWeight(),
        row.enabled(),
        row.description(),
        null,
        null);
  }

  @Override
  protected boolean upsertRow(PolicyRow row, String tenantId, String operatorId) {
    Map<String, Object> existing =
        tenantQuotaPolicyMapper.selectByUniqueKey(tenantId, row.policyCode());
    TenantQuotaPolicyUpsertParam param = new TenantQuotaPolicyUpsertParam();
    param.setTenantId(tenantId);
    param.setPolicyCode(row.policyCode());
    param.setMaxRunningJobsPerTenant(row.maxRunningJobsPerTenant());
    param.setMaxPartitionsPerTenant(row.maxPartitionsPerTenant());
    param.setMaxQpsPerTenant(row.maxQpsPerTenant());
    param.setFairShareWeight(row.fairShareWeight());
    param.setEnabled(row.enabled());
    param.setDescription(row.description());
    tenantQuotaPolicyMapper.upsertTenantQuotaPolicy(param);
    return existing == null || existing.isEmpty();
  }

  @Override
  protected void logChange(
      String tenantId,
      PolicyRow row,
      String reason,
      String operatorId,
      String traceId,
      String action) {
    configChangeLogMapper.insertConfigChangeLog(
        ConfigChangeLogBuilder.create(tenantId, operatorId, traceId)
            .forType("TENANT_QUOTA_POLICY")
            .withKey(row.policyCode())
            .action(action)
            .summary(
                changeSummaryJson(
                    reason,
                    mapOf(
                        "maxRunningJobsPerTenant",
                        row.maxRunningJobsPerTenant(),
                        "maxPartitionsPerTenant",
                        row.maxPartitionsPerTenant(),
                        "maxQpsPerTenant",
                        row.maxQpsPerTenant(),
                        "fairShareWeight",
                        row.fairShareWeight())))
            .build());
  }

  @Override
  protected void applyValidations(Sheet sheet) {
    addBooleanValidation(sheet, new int[] {6}, "enabled 填写提示", "请填写 TRUE 或 FALSE。");
  }

  @Override
  protected void createReadmeSheet(Workbook workbook) {
    Sheet sheet = workbook.createSheet("README");
    sheet.setColumnWidth(0, 16000);
    CellStyle titleStyle = createReadmeTitleStyle(workbook);
    String[] lines = {
      "tenant quota policy maintenance template",
      "1. Orange headers mark required fields. Hover the header to see field rules and"
          + " examples.",
      "2. policy_code is the unique key used during preview and apply.",
      "3. enabled has built-in dropdown validation (TRUE / FALSE).",
      "4. All integer fields must be >= 0, fair_share_weight must be >= 1.",
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

  @Override
  protected void createDictSheet(Workbook workbook) {
    Sheet sheet = workbook.createSheet("DICT");
    sheet.createFreezePane(0, 1, 0, 1);
    CellStyle dictHeaderStyle = ConsoleExcelStyles.createHeaderStyle(workbook);
    writeHeaders(sheet, List.of("field", "value", COL_DESCRIPTION), dictHeaderStyle);
    String[][] rows = {
      {COL_ENABLED, GUIDE_TRUE, COL_ENABLED},
      {COL_ENABLED, "FALSE", "disabled"}
    };
    for (int i = 0; i < rows.length; i++) {
      Row row = sheet.createRow(i + 1);
      row.createCell(0).setCellValue(rows[i][0]);
      row.createCell(1).setCellValue(rows[i][1]);
      row.createCell(2).setCellValue(rows[i][2]);
    }
    sheet.setColumnWidth(0, 24 * 256);
    sheet.setColumnWidth(1, 20 * 256);
    sheet.setColumnWidth(2, 36 * 256);
  }

  @Builder
  record PolicyRow(
      int rowNo,
      String tenantId,
      String policyCode,
      Integer maxRunningJobsPerTenant,
      Integer maxPartitionsPerTenant,
      Integer maxQpsPerTenant,
      Integer fairShareWeight,
      Boolean enabled,
      String description) {}
}
