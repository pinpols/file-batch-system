package com.example.batch.console.infrastructure;

import static com.example.batch.console.support.ConsoleExcelStyles.addBooleanValidation;
import static com.example.batch.console.support.ConsoleExcelStyles.addDropdownValidation;
import static com.example.batch.console.support.ConsoleExcelStyles.createReadmeTitleStyle;
import static com.example.batch.console.support.ConsoleExcelStyles.optionalColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.requiredColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.writeHeaders;

import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.QueuePriorityPolicy;
import com.example.batch.common.enums.ResourceQueueType;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.console.application.ConsoleResourceQueueExcelApplicationService;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.ResourceQueueMapper;
import com.example.batch.console.mapper.param.ResourceQueueUpsertParam;
import com.example.batch.console.support.ConsoleExcelStyles;
import com.example.batch.console.support.ConsoleExcelStyles.ColumnGuide;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.support.ExcelImportStore;
import com.example.batch.console.web.request.ExcelApplyRequest;
import com.example.batch.console.web.response.ConsoleResourceQueueResponse;
import com.example.batch.console.web.response.ExcelApplyResponse;
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

/** {@link ConsoleResourceQueueExcelApplicationService} 的默认实现。 */
@Service
public class DefaultConsoleResourceQueueExcelApplicationService
    extends AbstractSingleSheetExcelService<
        DefaultConsoleResourceQueueExcelApplicationService.QueueRow, ConsoleResourceQueueResponse>
    implements ConsoleResourceQueueExcelApplicationService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String COL_DESCRIPTION = "description";
  private static final String GUIDE_INT = "整数";
  private static final String GUIDE_TRUE = "TRUE";
  private static final String COL_QUEUE_TYPE = "queue_type";
  private static final String COL_PRIORITY_POLICY = "priority_policy";
  private static final String COL_ENABLED = "enabled";
  private static final String GUIDE_STR = "字符串";

  private static final String SHEET_NAME = "resource_queue";
  private static final List<String> COLUMNS =
      List.of(
          "tenant_id",
          "queue_code",
          "queue_name",
          COL_QUEUE_TYPE,
          "max_running_jobs",
          "max_running_partitions",
          "max_qps",
          "worker_group",
          "resource_tag",
          COL_PRIORITY_POLICY,
          "fair_share_weight",
          COL_ENABLED,
          COL_DESCRIPTION);
  private static final Set<String> QUEUE_TYPES = DictEnum.codes(ResourceQueueType.class);
  private static final Set<String> PRIORITY_POLICIES = DictEnum.codes(QueuePriorityPolicy.class);
  private static final Map<String, ColumnGuide> COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry(
              "tenant_id",
              optionalColumn("当前行所属租户。留空时，上传时自动使用当前租户。", GUIDE_STR, "tenant-a")),
          Map.entry(
              "queue_code", requiredColumn("队列唯一编码，作为导入匹配键。", GUIDE_STR, "QUEUE_IMPORT_01")),
          Map.entry("queue_name", requiredColumn("控制台展示的队列名称。", GUIDE_STR, "导入主队列")),
          Map.entry(
              COL_QUEUE_TYPE,
              requiredColumn("队列类型。", "枚举", "IMPORT", "IMPORT", "EXPORT", "DISPATCH", "MIXED")),
          Map.entry(
              "max_running_jobs", requiredColumn("最大并行作业数，必须 >= 0。", GUIDE_INT, "10")),
          Map.entry(
              "max_running_partitions", requiredColumn("最大并行分区数，必须 >= 0。", GUIDE_INT, "20")),
          Map.entry("max_qps", requiredColumn("最大 QPS 限制，必须 >= 0。", GUIDE_INT, "100")),
          Map.entry("worker_group", optionalColumn("指定 Worker 分组。", GUIDE_STR, "group-a")),
          Map.entry(
              "resource_tag", optionalColumn("资源标签，用于资源隔离。", GUIDE_STR, "high-priority")),
          Map.entry(
              COL_PRIORITY_POLICY,
              requiredColumn("优先级策略。", "枚举", "FIFO", "FIFO", "PRIORITY", "FAIR_SHARE")),
          Map.entry(
              "fair_share_weight", requiredColumn("公平调度权重，必须 >= 1。", GUIDE_INT, "1")),
          Map.entry(
              COL_ENABLED,
              optionalColumn("队列是否启用。", "布尔值", GUIDE_TRUE, GUIDE_TRUE, "FALSE")),
          Map.entry(COL_DESCRIPTION, optionalColumn("队列描述信息。", GUIDE_STR, "用于导入任务的主队列")));

  private final ResourceQueueMapper resourceQueueMapper;
  private final ConfigChangeLogMapper configChangeLogMapper;

  public DefaultConsoleResourceQueueExcelApplicationService(
      ConsoleTenantGuard tenantGuard,
      ConsoleRequestMetadataResolver requestMetadataResolver,
      ExcelImportStore importStore,
      ResourceQueueMapper resourceQueueMapper,
      ConfigChangeLogMapper configChangeLogMapper) {
    super(tenantGuard, requestMetadataResolver, importStore);
    this.resourceQueueMapper = resourceQueueMapper;
    this.configChangeLogMapper = configChangeLogMapper;
  }


  @Override
  public ResponseEntity<InputStreamResource> exportResourceQueues(
      String tenantId, String queueCode, String queueType, Boolean enabled) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    List<Map<String, Object>> rows =
        resourceQueueMapper.selectByQuery(resolvedTenantId, queueCode, queueType, enabled, null);
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
  protected QueueRow parseRow(
      String tenantId, int rowNo, Map<String, String> values, List<String> issues) {
    String effectiveTenant = resolveTenantField(values, tenantId, issues);
    return QueueRow.builder()
        .rowNo(rowNo)
        .tenantId(effectiveTenant)
        .queueCode(requireText(values, "queue_code", 128, issues))
        .queueName(requireText(values, "queue_name", 256, issues))
        .queueType(requireEnum(values, COL_QUEUE_TYPE, QUEUE_TYPES, 32, issues))
        .maxRunningJobs(requireInteger(values, "max_running_jobs", 0, issues))
        .maxRunningPartitions(requireInteger(values, "max_running_partitions", 0, issues))
        .maxQps(requireInteger(values, "max_qps", 0, issues))
        .workerGroup(optionalText(values, "worker_group", 128, issues))
        .resourceTag(optionalText(values, "resource_tag", 64, issues))
        .priorityPolicy(requireEnum(values, COL_PRIORITY_POLICY, PRIORITY_POLICIES, 32, issues))
        .fairShareWeight(requireInteger(values, "fair_share_weight", 1, issues))
        .enabled(optionalBoolean(values, COL_ENABLED, true, issues))
        .description(optionalText(values, COL_DESCRIPTION, 512, issues))
        .build();
  }

  @Override
  protected String rowUniqueKey(QueueRow row) {
    return row.queueCode();
  }

  @Override
  protected ConsoleResourceQueueResponse toResponse(QueueRow row) {
    return new ConsoleResourceQueueResponse(
        null,
        row.tenantId(),
        row.queueCode(),
        row.queueName(),
        row.queueType(),
        row.maxRunningJobs(),
        row.maxRunningPartitions(),
        row.maxQps(),
        row.workerGroup(),
        row.resourceTag(),
        row.priorityPolicy(),
        row.fairShareWeight(),
        row.enabled(),
        row.description(),
        null,
        null);
  }

  @Override
  protected boolean upsertRow(QueueRow row, String tenantId, String operatorId) {
    Map<String, Object> existing =
        resourceQueueMapper.selectByUniqueKey(tenantId, row.queueCode());
    ResourceQueueUpsertParam param = new ResourceQueueUpsertParam();
    param.setTenantId(tenantId);
    param.setQueueCode(row.queueCode());
    param.setQueueName(row.queueName());
    param.setQueueType(row.queueType());
    param.setMaxRunningJobs(row.maxRunningJobs());
    param.setMaxRunningPartitions(row.maxRunningPartitions());
    param.setMaxQps(row.maxQps());
    param.setWorkerGroup(row.workerGroup());
    param.setResourceTag(row.resourceTag());
    param.setPriorityPolicy(row.priorityPolicy());
    param.setFairShareWeight(row.fairShareWeight());
    param.setEnabled(row.enabled());
    param.setDescription(row.description());
    param.setCreatedBy(ConsoleTextSanitizer.safeInput(operatorId, 64));
    param.setUpdatedBy(ConsoleTextSanitizer.safeInput(operatorId, 64));
    resourceQueueMapper.upsertResourceQueue(param);
    return existing == null || existing.isEmpty();
  }

  @Override
  protected void logChange(
      String tenantId,
      QueueRow row,
      String reason,
      String operatorId,
      String traceId,
      String action) {
    configChangeLogMapper.insertConfigChangeLog(
        mapOf(
            "tenantId",
            tenantId,
            "configType",
            "RESOURCE_QUEUE",
            "configKey",
            row.queueCode(),
            "versionNo",
            1,
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
            changeSummaryJson(
                reason,
                mapOf(
                    "queueName",
                    row.queueName(),
                    "queueType",
                    row.queueType(),
                    "maxRunningJobs",
                    row.maxRunningJobs(),
                    "priorityPolicy",
                    row.priorityPolicy(),
                    "fairShareWeight",
                    row.fairShareWeight()))));
  }

  @Override
  protected void applyValidations(Sheet sheet) {
    addDropdownValidation(
        sheet, 3, QUEUE_TYPES.toArray(String[]::new), "queue_type 填写提示", "请从下拉列表中选择队列类型。");
    addDropdownValidation(
        sheet,
        9,
        PRIORITY_POLICIES.toArray(String[]::new),
        "priority_policy 填写提示",
        "请从下拉列表中选择优先级策略。");
    addBooleanValidation(sheet, new int[] {11}, "enabled 填写提示", "请填写 TRUE 或 FALSE。");
  }

  @Override
  protected void createReadmeSheet(Workbook workbook) {
    Sheet sheet = workbook.createSheet("README");
    sheet.setColumnWidth(0, 16000);
    CellStyle titleStyle = createReadmeTitleStyle(workbook);
    String[] lines = {
      "resource queue config maintenance template",
      "1. Orange headers mark required fields. Hover the header to see field rules and"
          + " examples.",
      "2. queue_code is the unique key used during preview and apply.",
      "3. queue_type, priority_policy, and enabled have built-in dropdown validation.",
      "4. max_running_jobs, max_running_partitions, max_qps must be >= 0; fair_share_weight"
          + " must be >= 1.",
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
      {COL_QUEUE_TYPE, "IMPORT", "import queue"},
      {COL_QUEUE_TYPE, "EXPORT", "export queue"},
      {COL_QUEUE_TYPE, "DISPATCH", "dispatch queue"},
      {COL_QUEUE_TYPE, "MIXED", "mixed queue"},
      {COL_PRIORITY_POLICY, "FIFO", "first in first out"},
      {COL_PRIORITY_POLICY, "PRIORITY", "priority based"},
      {COL_PRIORITY_POLICY, "FAIR_SHARE", "fair share scheduling"},
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
  record QueueRow(
      int rowNo,
      String tenantId,
      String queueCode,
      String queueName,
      String queueType,
      Integer maxRunningJobs,
      Integer maxRunningPartitions,
      Integer maxQps,
      String workerGroup,
      String resourceTag,
      String priorityPolicy,
      Integer fairShareWeight,
      Boolean enabled,
      String description) {}
}