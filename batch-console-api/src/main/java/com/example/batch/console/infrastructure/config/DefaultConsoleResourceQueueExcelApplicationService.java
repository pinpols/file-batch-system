package com.example.batch.console.infrastructure.config;

import static com.example.batch.console.support.excel.ConsoleExcelStyles.addDropdownValidation;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.createReadmeTitleStyle;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.optionalColumn;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.requiredColumn;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.setGuideColumnWidths;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.setReadmeColumnWidth;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.writeHeaders;

import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.QueuePriorityPolicy;
import com.example.batch.common.enums.ResourceQueueType;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.console.application.config.ConsoleResourceQueueExcelApplicationService;
import com.example.batch.console.domain.param.ResourceQueueUpsertParam;
import com.example.batch.console.infrastructure.excel.AbstractSingleSheetExcelService;
import com.example.batch.console.infrastructure.excel.ConfigPackageExcelSchema;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.ResourceQueueMapper;
import com.example.batch.console.support.ConfigChangeLogBuilder;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.support.excel.ConsoleExcelStyles;
import com.example.batch.console.support.excel.ConsoleExcelStyles.ColumnGuide;
import com.example.batch.console.support.excel.ExcelImportStore;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.request.excel.ExcelApplyRequest;
import com.example.batch.console.web.response.config.ConsoleResourceQueueResponse;
import com.example.batch.console.web.response.excel.ExcelApplyResponse;
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
  private static final List<String> COLUMNS = ConfigPackageExcelSchema.ResourceQueue.COLUMNS;
  private static final Set<String> QUEUE_TYPES = DictEnum.codes(ResourceQueueType.class);
  private static final Set<String> PRIORITY_POLICIES = DictEnum.codes(QueuePriorityPolicy.class);
  private static final Map<String, ColumnGuide> COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry(
              "tenant_id",
              optionalColumn(
                  "excel.queue.tenant_id.desc", "excel.guide.format.string", "tenant-a")),
          Map.entry(
              "queue_code",
              requiredColumn(
                  "excel.queue.queue_code.desc", "excel.guide.format.string", "QUEUE_IMPORT_01")),
          Map.entry(
              "queue_name",
              requiredColumn("excel.queue.queue_name.desc", "excel.guide.format.string", "导入主队列")),
          Map.entry(
              COL_QUEUE_TYPE,
              requiredColumn(
                  "excel.queue.queue_type.desc",
                  "excel.guide.format.enum",
                  "IMPORT",
                  "IMPORT",
                  "EXPORT",
                  "DISPATCH",
                  "MIXED")),
          Map.entry(
              "max_running_jobs",
              requiredColumn(
                  "excel.queue.max_running_jobs.desc", "excel.guide.format.integer", "10")),
          Map.entry(
              "max_running_partitions",
              requiredColumn(
                  "excel.queue.max_running_partitions.desc", "excel.guide.format.integer", "20")),
          Map.entry(
              "max_qps",
              requiredColumn("excel.queue.max_qps.desc", "excel.guide.format.integer", "100")),
          Map.entry(
              "worker_group",
              optionalColumn(
                  "excel.queue.worker_group.desc", "excel.guide.format.string", "group-a")),
          Map.entry(
              "resource_tag",
              optionalColumn(
                  "excel.queue.resource_tag.desc", "excel.guide.format.string", "high-priority")),
          Map.entry(
              COL_PRIORITY_POLICY,
              requiredColumn(
                  "excel.queue.priority_policy.desc",
                  "excel.guide.format.enum",
                  "FIFO",
                  "FIFO",
                  "PRIORITY",
                  "FAIR_SHARE")),
          Map.entry(
              "fair_share_weight",
              requiredColumn(
                  "excel.queue.fair_share_weight.desc", "excel.guide.format.integer", "1")),
          Map.entry(
              COL_ENABLED,
              optionalColumn(
                  "excel.queue.enabled.desc",
                  "excel.guide.format.boolean",
                  GUIDE_TRUE,
                  GUIDE_TRUE,
                  "FALSE")),
          Map.entry(
              COL_DESCRIPTION,
              optionalColumn(
                  "excel.queue.description.desc", "excel.guide.format.string", "用于导入任务的主队列")));

  private final ResourceQueueMapper resourceQueueMapper;
  private final ConfigChangeLogMapper configChangeLogMapper;

  public DefaultConsoleResourceQueueExcelApplicationService(
      ConsoleTenantGuard tenantGuard,
      ConsoleRequestMetadataResolver requestMetadataResolver,
      ExcelImportStore importStore,
      BatchDateTimeSupport dateTimeSupport,
      MessageSource messageSource,
      ResourceQueueMapper resourceQueueMapper,
      ConfigChangeLogMapper configChangeLogMapper) {
    super(tenantGuard, requestMetadataResolver, importStore, dateTimeSupport, messageSource);
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
    Map<String, Object> existing = resourceQueueMapper.selectByUniqueKey(tenantId, row.queueCode());
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
  protected boolean rowExists(QueueRow row, String tenantId) {
    Map<String, Object> existing = resourceQueueMapper.selectByUniqueKey(tenantId, row.queueCode());
    return existing != null && !existing.isEmpty();
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
        ConfigChangeLogBuilder.create(tenantId, operatorId, traceId)
            .forType("RESOURCE_QUEUE")
            .withKey(row.queueCode())
            .action(action)
            .summary(
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
                        row.fairShareWeight())))
            .build());
  }

  @Override
  protected void applyValidations(Sheet sheet) {
    Locale locale = LocaleContextHolder.getLocale();
    addDropdownValidation(
        sheet,
        3,
        QUEUE_TYPES.toArray(String[]::new),
        "excel.queue.queue_type.prompt_title",
        "excel.queue.queue_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        9,
        PRIORITY_POLICIES.toArray(String[]::new),
        "excel.queue.priority_policy.prompt_title",
        "excel.queue.priority_policy.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        11,
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
      "excel.queue.readme.title",
      "excel.queue.readme.line1",
      "excel.queue.readme.line2",
      "excel.queue.readme.line3",
      "excel.queue.readme.line4",
      "excel.queue.readme.line5"
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
    setGuideColumnWidths(sheet);
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
