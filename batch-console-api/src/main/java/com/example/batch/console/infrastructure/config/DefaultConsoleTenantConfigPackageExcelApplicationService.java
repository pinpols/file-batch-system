package com.example.batch.console.infrastructure.config;

import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.*;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelWorkbookWriter.*;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.CodeNormalizer;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.Texts;
import com.example.batch.console.application.config.ConsoleTenantConfigPackageExcelApplicationService;
import com.example.batch.console.domain.entity.JobDefinitionEntity;
import com.example.batch.console.domain.param.FileChannelConfigUpsertParam;
import com.example.batch.console.domain.param.JobDefinitionMaintenanceUpdateParam;
import com.example.batch.console.domain.query.FileTemplateConfigQuery;
import com.example.batch.console.domain.query.JobDefinitionQuery;
import com.example.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import com.example.batch.console.domain.workflow.mapper.PipelineDefinitionMapper;
import com.example.batch.console.domain.workflow.mapper.PipelineStepDefinitionMapper;
import com.example.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import com.example.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import com.example.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import com.example.batch.console.domain.workflow.param.WorkflowDefinitionUpsertParam;
import com.example.batch.console.domain.workflow.param.WorkflowEdgeUpsertParam;
import com.example.batch.console.domain.workflow.param.WorkflowNodeUpsertParam;
import com.example.batch.console.domain.workflow.query.WorkflowDefinitionQuery;
import com.example.batch.console.infrastructure.excel.BatchWindowExcelRowParser;
import com.example.batch.console.infrastructure.excel.BatchWindowExcelRowParser.WindowRow;
import com.example.batch.console.infrastructure.excel.BusinessCalendarExcelRowParser;
import com.example.batch.console.infrastructure.excel.BusinessCalendarExcelRowParser.CalendarRow;
import com.example.batch.console.infrastructure.excel.ConfigPackageExcelSchema;
import com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator;
import com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.PackageValidationResult;
import com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.SheetResult;
import com.example.batch.console.infrastructure.excel.ConfigPackageExcelWorkbookWriter;
import com.example.batch.console.infrastructure.excel.FileTemplateExcelRowParser;
import com.example.batch.console.infrastructure.excel.FileTemplateExcelRowParser.TemplateRow;
import com.example.batch.console.infrastructure.excel.ResourceQueueExcelRowParser;
import com.example.batch.console.infrastructure.excel.ResourceQueueExcelRowParser.QueueRow;
import com.example.batch.console.mapper.BatchWindowMapper;
import com.example.batch.console.mapper.BusinessCalendarMapper;
import com.example.batch.console.mapper.CalendarHolidayMapper;
import com.example.batch.console.mapper.FileChannelConfigMapper;
import com.example.batch.console.mapper.FileTemplateConfigMapper;
import com.example.batch.console.mapper.JobDefinitionMapper;
import com.example.batch.console.mapper.ResourceQueueMapper;
import com.example.batch.console.mapper.StepRegistryQueryMapper;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport;
import com.example.batch.console.support.excel.ConsoleSingleSheetExcelImportSupport;
import com.example.batch.console.support.excel.TenantConfigPackageExcelImportStore;
import com.example.batch.console.support.excel.TenantConfigPackageExcelImportStore.PackageExcelSession;
import com.example.batch.console.support.web.ConsoleRequestMetadata;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.web.UploadFileGuard;
import com.example.batch.console.web.request.config.TenantConfigPackageExcelApplyRequest;
import com.example.batch.console.web.response.config.TenantConfigPackageExcelApplyResponse;
import com.example.batch.console.web.response.config.TenantConfigPackageExcelPreviewResponse;
import com.example.batch.console.web.response.config.TenantConfigPackageExcelPreviewResponse.IssueDto;
import com.example.batch.console.web.response.config.TenantConfigPackageExcelPreviewResponse.SheetStats;
import com.example.batch.console.web.response.config.TenantConfigPackageExcelUploadResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.context.MessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 租户配置包 Excel 的全生命周期管理：export / template / upload → preview → apply。
 *
 * <p><b>3 阶段导入流程</b>：
 *
 * <ol>
 *   <li>{@link #upload} — 解析 Excel 字节流（11 sheet），构建 {@code PackageExcelSession} 存入 {@link
 *       TenantConfigPackageExcelImportStore}，返回短期 token（内存 TTL）。
 *   <li>{@link #preview} — 用 token 取回 session，调 {@link ConfigPackageExcelValidator} 做 跨 sheet
 *       依赖校验（如 pipelineStep 引用的 jobCode 必须存在），返回每 sheet 的 valid/invalid 统计和逐行错误列表，不写库。
 *   <li>{@link #apply} — 再次 validate；若 {@code totalInvalid > 0} 直接拒绝；否则在单事务内 按 resourceQueue →
 *       businessCalendar → batchWindow → fileTemplate → channel → job → pipeline+step →
 *       workflow+node+edge 顺序写库， 完成后 {@code importStore.remove(token)}。
 * </ol>
 *
 * <p><b>11 sheets</b>（顺序即写库顺序）：resource_queue、business_calendar、batch_window、job、
 * file_channel、file_template、pipeline_definition、pipeline_step、workflow_definition、workflow_node、
 * workflow_edge。
 *
 * <p><b>多级结构写法</b>：
 *
 * <ul>
 *   <li>Pipeline：步骤按 {@code jobCode:version} 分组后与父行对应，apply 时先删再重插 step。
 *   <li>Workflow：节点和边按 {@code wfCode:version} 分组，upsert 节点/边（不删旧节点，依赖 Mapper 的 ON CONFLICT UPDATE
 *       语义）。
 * </ul>
 *
 * <p><b>租户安全</b>：{@code upload} 从 header 解析租户（拒绝客户端传入），{@link #loadSession} 每次访问都调 {@link
 * ConsoleTenantGuard#assertTenantAllowed} 确保 token 持有者与当前请求租户一致。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleTenantConfigPackageExcelApplicationService
    implements ConsoleTenantConfigPackageExcelApplicationService {

  private static final String KEY_ID = "id";

  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final TenantConfigPackageExcelImportStore importStore;
  private final JobDefinitionMapper jobDefinitionMapper;
  private final ResourceQueueMapper resourceQueueMapper;
  private final BusinessCalendarMapper businessCalendarMapper;
  private final CalendarHolidayMapper calendarHolidayMapper;
  private final BatchWindowMapper batchWindowMapper;
  private final FileChannelConfigMapper fileChannelConfigMapper;
  private final FileTemplateConfigMapper fileTemplateConfigMapper;
  private final PipelineDefinitionMapper pipelineDefinitionMapper;
  private final PipelineStepDefinitionMapper pipelineStepDefinitionMapper;
  private final WorkflowDefinitionMapper workflowDefinitionMapper;
  private final WorkflowNodeMapper workflowNodeMapper;
  private final WorkflowEdgeMapper workflowEdgeMapper;
  private final StepRegistryQueryMapper stepRegistryQueryMapper;
  private final TenantConfigPackageRowProjections rowProjections;
  private final BatchDateTimeSupport dateTimeSupport;
  private final MessageSource messageSource;

  private ConfigPackageExcelValidator validator() {
    return new ConfigPackageExcelValidator(
        jobDefinitionMapper,
        pipelineDefinitionMapper,
        stepRegistryQueryMapper,
        fileTemplateConfigMapper,
        resourceQueueMapper,
        businessCalendarMapper,
        batchWindowMapper);
  }

  private ConfigPackageExcelWorkbookWriter workbookWriter() {
    return new ConfigPackageExcelWorkbookWriter(messageSource);
  }

  @Override
  public ResponseEntity<StreamingResponseBody> exportPackage(String tenantId) {
    String tid = tenantGuard.resolveTenant(tenantId);
    // R2-P1-9: 数据快照在请求线程里取（事务上下文），但 workbook 写入流的动作推迟到
    // StreamingResponseBody 阶段——Spring 在响应渲染时调用 lambda，避免堆里 buffer 整份 byte[]。
    List<Map<String, Object>> resourceQueues =
        resourceQueueMapper.selectByQuery(tid, null, null, null, null);
    List<Map<String, Object>> businessCalendars =
        withCalendarHolidayValues(businessCalendarMapper.selectByQuery(tid, null, null, null));
    List<Map<String, Object>> batchWindows = batchWindowMapper.selectByQuery(tid, null, null, null);
    List<Map<String, Object>> jobs =
        rowProjections.toJobRows(
            jobDefinitionMapper.selectByQuery(JobDefinitionQuery.ofTenant(tid, null)));
    List<Map<String, Object>> channels =
        fileChannelConfigMapper.selectByQuery(tid, null, null, null, null);
    List<Map<String, Object>> fileTemplates =
        fileTemplateConfigMapper.selectByQuery(FileTemplateConfigQuery.ofTenant(tid, null));
    List<Map<String, Object>> pipelines =
        pipelineDefinitionMapper.selectByQuery(tid, null, null, null, null);
    List<Map<String, Object>> steps = rowProjections.collectPipelineSteps(pipelines);
    List<WorkflowDefinitionEntity> wfEntities =
        workflowDefinitionMapper.selectByQuery(WorkflowDefinitionQuery.ofTenant(tid, null));
    List<Map<String, Object>> wfDefs = rowProjections.toWfDefRows(wfEntities);
    List<Map<String, Object>> wfNodes = rowProjections.collectWorkflowNodes(tid, wfEntities);
    List<Map<String, Object>> wfEdges = rowProjections.collectWorkflowEdges(tid, wfEntities);
    List<List<Map<String, Object>>> sheets =
        List.of(
            resourceQueues,
            businessCalendars,
            batchWindows,
            jobs,
            channels,
            fileTemplates,
            pipelines,
            steps,
            wfDefs,
            wfNodes,
            wfEdges);
    Map<String, List<String>> implRegistry = loadRegisteredImplCodesByModule();
    ConfigPackageExcelWorkbookWriter writer = workbookWriter();
    String fileName =
        "tenant-config-package-" + tid + "-" + dateTimeSupport.currentFileTimestamp() + ".xlsx";
    return ConsoleSingleSheetExcelImportSupport.excelStreamingResponse(
        fileName, out -> writer.writeExportWorkbook(out, sheets, implRegistry));
  }

  @Override
  public ResponseEntity<StreamingResponseBody> downloadTemplate() {
    ConfigPackageExcelWorkbookWriter writer = workbookWriter();
    Map<String, List<String>> implRegistry = loadRegisteredImplCodesByModule();
    return ConsoleSingleSheetExcelImportSupport.excelStreamingResponse(
        "tenant-config-package-template.xlsx",
        out -> writer.writeTemplateWorkbook(out, implRegistry));
  }

  /**
   * 从 {@code batch.step_registry} 查 (module → bean 列表)，供 Excel 模板 / 导出的 impl_code 下拉用。
   * 查询失败或结果为空时返回空 map，writer 会降级为不加下拉（首次部署无 worker 启动的兼容路径）。
   */
  private Map<String, List<String>> loadRegisteredImplCodesByModule() {
    Map<String, List<String>> result = new LinkedHashMap<>();
    try {
      for (Map<String, String> row : stepRegistryQueryMapper.selectAllImplEntries()) {
        String module = row.get("module");
        String implCode = row.get("implCode");
        if (module == null || implCode == null) {
          continue;
        }
        result.computeIfAbsent(module, k -> new ArrayList<>()).add(implCode);
      }
    } catch (RuntimeException ignored) {
      SwallowedExceptionLogger.warn(
          DefaultConsoleTenantConfigPackageExcelApplicationService.class,
          "catch:RuntimeException",
          ignored);

      // step_registry 表尚未创建 / 查询失败时降级为空，writer 跳过 impl_code 下拉
    }
    return result;
  }

  private List<Map<String, Object>> withCalendarHolidayValues(List<Map<String, Object>> rows) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      out.add(
          ConfigPackageExcelSchema.BusinessCalendar.toExportRow(
              row, calendarHolidaysText(row.get(KEY_ID))));
    }
    return out;
  }

  private String calendarHolidaysText(Object idValue) {
    if (!(idValue instanceof Number number)) {
      return null;
    }
    List<String> dates =
        calendarHolidayMapper.selectByCalendarId(number.longValue()).stream()
            .map(row -> String.valueOf(row.get("bizDate")))
            .toList();
    return dates.isEmpty() ? null : String.join(",", dates);
  }

  @Override
  public TenantConfigPackageExcelUploadResponse upload(MultipartFile file, String requestTenantId)
      throws IOException {
    Guard.require(file != null && !file.isEmpty(), "file is required");
    UploadFileGuard.requireExcel(file);
    String tenantId = tenantGuard.resolveTenant(requestTenantId);
    String fileName = fileNameOrDefault(file.getOriginalFilename());
    PackageExcelSession session = parseWorkbook(file.getBytes(), tenantId, fileName);
    String token = importStore.save(session);
    return new TenantConfigPackageExcelUploadResponse(
        token,
        fileName,
        session.resourceQueueRows().size(),
        session.businessCalendarRows().size(),
        session.batchWindowRows().size(),
        session.jobRows().size(),
        session.fileChannelRows().size(),
        session.fileTemplateRows().size(),
        session.pipelineRows().size(),
        session.pipelineStepRows().size(),
        session.workflowDefinitionRows().size(),
        session.workflowNodeRows().size(),
        session.workflowEdgeRows().size());
  }

  @Override
  public TenantConfigPackageExcelPreviewResponse preview(String uploadToken) {
    PackageExcelSession session = loadSession(uploadToken);
    PackageValidationResult result = validator().validate(session);
    return toPreviewResponse(uploadToken, session.fileName(), result);
  }

  @Override
  public ResponseEntity<StreamingResponseBody> downloadPreviewWorkbook(String uploadToken) {
    PackageExcelSession session = loadSession(uploadToken);
    PackageValidationResult result = validator().validate(session);
    Map<String, List<String>> implRegistry = loadRegisteredImplCodesByModule();
    ConfigPackageExcelWorkbookWriter writer = workbookWriter();
    return ConsoleSingleSheetExcelImportSupport.excelStreamingResponse(
        ConsoleExcelPreviewWorkbookSupport.previewWorkbookFileName(session.fileName()),
        out -> writer.writePreviewWorkbook(out, session, result, implRegistry));
  }

  @Override
  @Transactional
  public TenantConfigPackageExcelApplyResponse apply(
      String uploadToken, TenantConfigPackageExcelApplyRequest request) {
    PackageExcelSession session = loadSession(uploadToken);
    PackageValidationResult result = validator().validate(session);
    if (result.totalInvalid() > 0) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.excel.invalid_rows");
    }
    ConsoleRequestMetadata metadata = requestMetadataResolver.current();
    ApplyContext ctx =
        new ApplyContext(
            session.tenantId(), metadata.operatorId(), request.getReason(), metadata.traceId());

    ApplyStats resourceQueueStats = applyResourceQueues(result.validResourceQueues(), ctx);
    ApplyStats businessCalendarStats = applyBusinessCalendars(result.validBusinessCalendars(), ctx);
    ApplyStats batchWindowStats = applyBatchWindows(result.validBatchWindows(), ctx);
    ApplyStats fileTemplateStats = applyFileTemplates(result.validFileTemplates(), ctx);
    ApplyStats channelStats = applyChannels(result.validChannels(), ctx);
    ApplyStats jobStats = applyJobs(result.validJobs(), ctx);
    ApplyStats pipelineStats = applyPipelines(result.validPipelines(), result.validSteps(), ctx);
    ApplyStats wfStats =
        applyWorkflows(result.validWfDefs(), result.validWfNodes(), result.validWfEdges(), ctx);

    importStore.remove(uploadToken);
    return new TenantConfigPackageExcelApplyResponse(
        uploadToken,
        session.tenantId(),
        resourceQueueStats.inserted(),
        resourceQueueStats.updated(),
        businessCalendarStats.inserted(),
        businessCalendarStats.updated(),
        batchWindowStats.inserted(),
        batchWindowStats.updated(),
        jobStats.inserted(),
        jobStats.updated(),
        channelStats.inserted(),
        channelStats.updated(),
        fileTemplateStats.inserted(),
        fileTemplateStats.updated(),
        pipelineStats.inserted(),
        pipelineStats.updated(),
        wfStats.inserted(),
        wfStats.updated());
  }

  private PackageExcelSession parseWorkbook(byte[] bytes, String tenantId, String fileName) {
    try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      return new PackageExcelSession(
          fileName,
          tenantId,
          dateTimeSupport.nowInstant(),
          parseOptionalSheet(wb, RESOURCE_QUEUE_SHEET, RESOURCE_QUEUE_COLUMNS, tenantId),
          parseOptionalSheet(wb, BUSINESS_CALENDAR_SHEET, BUSINESS_CALENDAR_COLUMNS, tenantId),
          parseOptionalSheet(wb, BATCH_WINDOW_SHEET, BATCH_WINDOW_COLUMNS, tenantId),
          parseSheet(wb, JOB_SHEET, JOB_COLUMNS, tenantId),
          parseSheet(wb, CHANNEL_SHEET, CHANNEL_COLUMNS, tenantId),
          parseSheet(wb, FILE_TEMPLATE_SHEET, FILE_TEMPLATE_COLUMNS, tenantId),
          parseSheet(wb, PIPELINE_SHEET, PIPELINE_COLUMNS, tenantId),
          parseSheet(wb, STEP_SHEET, STEP_COLUMNS, null),
          parseSheet(wb, WF_DEF_SHEET, WF_DEF_COLUMNS, tenantId),
          parseSheet(wb, WF_NODE_SHEET, WF_NODE_COLUMNS, tenantId),
          parseSheet(wb, WF_EDGE_SHEET, WF_EDGE_COLUMNS, tenantId));
    } catch (BizException e) {
      throw e;
    } catch (Exception e) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "failed to read excel workbook: " + e.getMessage());
    }
  }

  private List<Map<String, String>> parseSheet(
      Workbook wb, String sheetName, List<String> columns, String tenantId) {
    Sheet sheet = wb.getSheet(sheetName);
    Guard.require(sheet != null, "excel sheet missing: " + sheetName);
    DataFormatter fmt = new DataFormatter();
    Row headerRow = sheet.getRow(sheet.getFirstRowNum());
    Guard.require(headerRow != null, "header row missing in sheet: " + sheetName);
    Map<String, Integer> headerIndex = buildHeaderIndex(headerRow, fmt);
    validateSheetHeaders(sheetName, headerIndex, Set.copyOf(columns));
    List<Map<String, String>> rows = new ArrayList<>();
    for (int i = headerRow.getRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
      Row row = sheet.getRow(i);
      if (row == null || isRowBlank(row, fmt)) {
        continue;
      }
      Map<String, String> values = new LinkedHashMap<>();
      for (String col : columns) {
        Integer colIdx = headerIndex.get(col);
        values.put(col, normalize(cellText(row, colIdx, fmt)));
      }
      if (tenantId != null && !Texts.hasText(values.get(COL_TENANT_ID))) {
        values.put(COL_TENANT_ID, tenantId);
      }
      rows.add(values);
    }
    return rows;
  }

  private List<Map<String, String>> parseOptionalSheet(
      Workbook wb, String sheetName, List<String> columns, String tenantId) {
    return wb.getSheet(sheetName) == null
        ? List.of()
        : parseSheet(wb, sheetName, columns, tenantId);
  }

  private ApplyStats applyResourceQueues(List<Map<String, String>> rows, ApplyContext ctx) {
    int inserted = 0, updated = 0;
    for (Map<String, String> row : rows) {
      List<String> issues = new ArrayList<>();
      QueueRow queue = ResourceQueueExcelRowParser.parseRow(ctx.tenantId(), 0, row, issues);
      if (!issues.isEmpty()) {
        throw invalidParsedRow(RESOURCE_QUEUE_SHEET, issues);
      }
      Map<String, Object> existing =
          resourceQueueMapper.selectByUniqueKey(ctx.tenantId(), queue.queueCode());
      resourceQueueMapper.upsertResourceQueue(
          ResourceQueueExcelRowParser.toUpsertParam(queue, ctx.operatorId()));
      if (existing == null || existing.isEmpty()) {
        inserted++;
      } else {
        updated++;
      }
    }
    return new ApplyStats(inserted, updated);
  }

  private ApplyStats applyBusinessCalendars(List<Map<String, String>> rows, ApplyContext ctx) {
    int inserted = 0, updated = 0;
    for (Map<String, String> row : rows) {
      List<String> issues = new ArrayList<>();
      CalendarRow calendar =
          BusinessCalendarExcelRowParser.parseRow(ctx.tenantId(), 0, row, issues);
      if (!issues.isEmpty()) {
        throw invalidParsedRow(BUSINESS_CALENDAR_SHEET, issues);
      }
      Map<String, Object> existing =
          businessCalendarMapper.selectActiveByTenantAndCalendarCode(
              ctx.tenantId(), calendar.calendarCode());
      businessCalendarMapper.upsertBusinessCalendar(
          BusinessCalendarExcelRowParser.toUpsertParam(calendar, safeOp(ctx.operatorId())));
      applyCalendarHolidays(ctx.tenantId(), calendar);
      if (existing == null || existing.isEmpty()) {
        inserted++;
      } else {
        updated++;
      }
    }
    return new ApplyStats(inserted, updated);
  }

  private ApplyStats applyBatchWindows(List<Map<String, String>> rows, ApplyContext ctx) {
    int inserted = 0, updated = 0;
    for (Map<String, String> row : rows) {
      List<String> issues = new ArrayList<>();
      WindowRow window = BatchWindowExcelRowParser.parseRow(ctx.tenantId(), 0, row, issues);
      if (!issues.isEmpty()) {
        throw invalidParsedRow(BATCH_WINDOW_SHEET, issues);
      }
      Map<String, Object> existing =
          batchWindowMapper.selectByUniqueKey(ctx.tenantId(), window.windowCode());
      batchWindowMapper.upsertBatchWindow(BatchWindowExcelRowParser.toUpsertParam(window));
      if (existing == null || existing.isEmpty()) {
        inserted++;
      } else {
        updated++;
      }
    }
    return new ApplyStats(inserted, updated);
  }

  private void applyCalendarHolidays(String tenantId, CalendarRow calendar) {
    Map<String, Object> saved =
        businessCalendarMapper.selectActiveByTenantAndCalendarCode(
            tenantId, calendar.calendarCode());
    if (saved == null || saved.get(KEY_ID) == null) {
      return;
    }
    Long calendarId = ((Number) saved.get(KEY_ID)).longValue();
    calendarHolidayMapper.deleteByCalendarId(calendarId);
    if (calendar.holidays() == null || calendar.holidays().isEmpty()) {
      return;
    }
    List<Map<String, Object>> params = new ArrayList<>();
    for (LocalDate holiday : calendar.holidays()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("tenantId", tenantId); // NOT NULL 列必填，与 V*__tenant_isolation 加固一致
      item.put("calendarId", calendarId);
      item.put("bizDate", holiday);
      item.put("dayType", "HOLIDAY");
      item.put("holidayName", null);
      item.put(COL_DESCRIPTION, calendar.description());
      params.add(item);
    }
    calendarHolidayMapper.batchInsert(params);
  }

  private static BizException invalidParsedRow(String sheetName, List<String> issues) {
    return BizException.of(
        ResultCode.INVALID_ARGUMENT,
        "error.common.invalid_argument_detail",
        "invalid " + sheetName + " row: " + issues);
  }

  private ApplyStats applyJobs(List<Map<String, String>> rows, ApplyContext ctx) {
    int inserted = 0, updated = 0;
    for (Map<String, String> row : rows) {
      String jobCode = normalize(row.get(COL_JOB_CODE));
      JobDefinitionEntity existing = jobDefinitionMapper.selectByUniqueKey(ctx.tenantId(), jobCode);
      if (existing == null) {
        JobDefinitionEntity entity = new JobDefinitionEntity();
        entity.setTenantId(ctx.tenantId());
        entity.setJobCode(jobCode);
        entity.setJobName(normalize(row.get(COL_JOB_NAME)));
        entity.setJobType(normalizeEnum(row.get(COL_JOB_TYPE)));
        entity.setBizType(normalize(row.get(COL_BIZ_TYPE)));
        entity.setQueueCode(CodeNormalizer.toConfigFormOrNull(row.get(COL_QUEUE_CODE)));
        entity.setWorkerGroup(CodeNormalizer.toUpperOrNull(row.get(COL_WORKER_GROUP)));
        entity.setScheduleType(normalizeEnum(row.get(COL_SCHEDULE_TYPE)));
        entity.setScheduleExpr(normalize(row.get(COL_SCHEDULE_EXPR)));
        entity.setCalendarCode(CodeNormalizer.toConfigFormOrNull(row.get(COL_CALENDAR_CODE)));
        entity.setWindowCode(CodeNormalizer.toConfigFormOrNull(row.get(COL_WINDOW_CODE)));
        entity.setRetryPolicy(normalizeEnum(row.get(COL_RETRY_POLICY)));
        entity.setRetryMaxCount(parseInteger(row.get(COL_RETRY_MAX_COUNT)));
        entity.setTimeoutSeconds(parseInteger(row.get(COL_TIMEOUT_SECONDS)));
        entity.setShardStrategy(normalizeEnum(row.get(COL_SHARD_STRATEGY)));
        entity.setExecutionHandler(normalize(row.get(COL_EXECUTION_HANDLER)));
        entity.setParamSchema(normalize(row.get(COL_PARAM_SCHEMA)));
        entity.setDefaultParams(normalize(row.get(COL_DEFAULT_PARAMS)));
        entity.setEnabled(parseBoolean(row.get(COL_ENABLED), true));
        entity.setDescription(normalize(row.get(COL_DESCRIPTION)));
        entity.setCreatedBy(safeOp(ctx.operatorId()));
        entity.setUpdatedBy(safeOp(ctx.operatorId()));
        jobDefinitionMapper.insert(entity);
        inserted++;
      } else {
        JobDefinitionMaintenanceUpdateParam param = new JobDefinitionMaintenanceUpdateParam();
        param.setTenantId(ctx.tenantId());
        param.setJobCode(jobCode);
        param.setJobName(normalize(row.get(COL_JOB_NAME)));
        param.setQueueCode(CodeNormalizer.toConfigFormOrNull(row.get(COL_QUEUE_CODE)));
        param.setWorkerGroup(CodeNormalizer.toUpperOrNull(row.get(COL_WORKER_GROUP)));
        param.setScheduleExpr(normalize(row.get(COL_SCHEDULE_EXPR)));
        param.setCalendarCode(CodeNormalizer.toConfigFormOrNull(row.get(COL_CALENDAR_CODE)));
        param.setWindowCode(CodeNormalizer.toConfigFormOrNull(row.get(COL_WINDOW_CODE)));
        param.setRetryPolicy(normalizeEnum(row.get(COL_RETRY_POLICY)));
        param.setRetryMaxCount(parseInteger(row.get(COL_RETRY_MAX_COUNT)));
        param.setTimeoutSeconds(parseInteger(row.get(COL_TIMEOUT_SECONDS)));
        param.setShardStrategy(normalizeEnum(row.get(COL_SHARD_STRATEGY)));
        param.setEnabled(parseBoolean(row.get(COL_ENABLED), true));
        param.setDescription(normalize(row.get(COL_DESCRIPTION)));
        param.setUpdatedBy(safeOp(ctx.operatorId()));
        jobDefinitionMapper.updateJobDefinitionMaintenance(param);
        updated++;
      }
    }
    return new ApplyStats(inserted, updated);
  }

  private ApplyStats applyChannels(List<Map<String, String>> rows, ApplyContext ctx) {
    int inserted = 0, updated = 0;
    for (Map<String, String> row : rows) {
      String code = normalize(row.get(COL_CHANNEL_CODE));
      Map<String, Object> existing =
          fileChannelConfigMapper.selectByUniqueKey(ctx.tenantId(), code);
      FileChannelConfigUpsertParam param = new FileChannelConfigUpsertParam();
      param.setTenantId(ctx.tenantId());
      param.setChannelCode(code);
      param.setChannelName(normalize(row.get(COL_CHANNEL_NAME)));
      param.setChannelType(normalizeEnum(row.get(COL_CHANNEL_TYPE)));
      param.setTargetEndpoint(normalize(row.get("target_endpoint")));
      param.setAuthType(normalizeEnum(row.get(COL_AUTH_TYPE)));
      param.setConfigJson(row.get(COL_CONFIG_JSON));
      param.setReceiptPolicy(normalizeEnum(row.get(COL_RECEIPT_POLICY)));
      param.setTimeoutSeconds(parseInteger(row.get(COL_TIMEOUT_SECONDS)));
      param.setEnabled(parseBoolean(row.get(COL_ENABLED), true));
      param.setCreatedBy(safeOp(ctx.operatorId()));
      param.setUpdatedBy(safeOp(ctx.operatorId()));
      fileChannelConfigMapper.upsertFileChannelConfig(param);
      if (existing == null || existing.isEmpty()) {
        inserted++;
      } else {
        updated++;
      }
    }
    return new ApplyStats(inserted, updated);
  }

  private ApplyStats applyFileTemplates(List<Map<String, String>> rows, ApplyContext ctx) {
    int inserted = 0, updated = 0;
    for (Map<String, String> row : rows) {
      List<String> issues = new ArrayList<>();
      TemplateRow template = FileTemplateExcelRowParser.parseRow(ctx.tenantId(), 0, row, issues);
      if (!issues.isEmpty()) {
        throw BizException.of(
            ResultCode.INVALID_ARGUMENT,
            "error.common.invalid_argument_detail",
            "invalid file_template_config row: " + issues);
      }
      Map<String, Object> existing =
          fileTemplateConfigMapper.selectByUniqueKey(
              ctx.tenantId(), template.templateCode(), template.version());
      fileTemplateConfigMapper.upsertFileTemplateConfig(
          FileTemplateExcelRowParser.toUpsertParam(ctx.tenantId(), template, ctx.operatorId()));
      if (existing == null || existing.isEmpty()) {
        inserted++;
      } else {
        updated++;
      }
    }
    return new ApplyStats(inserted, updated);
  }

  private ApplyStats applyPipelines(
      List<Map<String, String>> pipelineRows,
      List<Map<String, String>> stepRows,
      ApplyContext ctx) {
    Map<String, List<Map<String, String>>> stepsByKey =
        stepRows.stream()
            .collect(
                Collectors.groupingBy(
                    r ->
                        normalize(r.get(COL_JOB_CODE))
                            + KEY_SEP_COLON
                            + normalize(r.get(COL_VERSION))));
    int inserted = 0, updated = 0;
    for (Map<String, String> row : pipelineRows) {
      String jobCode = normalize(row.get(COL_JOB_CODE));
      int version = Integer.parseInt(normalize(row.get(COL_VERSION)));
      Map<String, Object> existing =
          pipelineDefinitionMapper.selectByUniqueKey(ctx.tenantId(), jobCode, version);
      Long pipelineId;
      if (existing == null || existing.isEmpty()) {
        Map<String, Object> params = buildPipelineInsertParams(row, ctx);
        pipelineDefinitionMapper.insert(params);
        pipelineId = ((Number) params.get(KEY_ID)).longValue();
        inserted++;
      } else {
        pipelineId = ((Number) existing.get(KEY_ID)).longValue();
        pipelineDefinitionMapper.update(buildPipelineUpdateParams(pipelineId, row, ctx));
        updated++;
      }
      pipelineStepDefinitionMapper.deleteByPipelineDefinitionId(pipelineId);
      List<Map<String, String>> stepsForPipeline =
          stepsByKey.getOrDefault(jobCode + KEY_SEP_COLON + version, List.of());
      if (!stepsForPipeline.isEmpty()) {
        // Excel 导入大批量场景:同一 pipeline 平均 5-10 step,百级 pipeline 导入时
        // 单插循环放大 5-10x;批量插入折成 1 次往返。
        List<Map<String, Object>> batchStepRows = new ArrayList<>(stepsForPipeline.size());
        for (Map<String, String> step : stepsForPipeline) {
          batchStepRows.add(buildStepInsertParams(pipelineId, step));
        }
        pipelineStepDefinitionMapper.insertBatch(batchStepRows);
      }
    }
    return new ApplyStats(inserted, updated);
  }

  private ApplyStats applyWorkflows(
      List<Map<String, String>> defRows,
      List<Map<String, String>> nodeRows,
      List<Map<String, String>> edgeRows,
      ApplyContext ctx) {
    Map<String, List<Map<String, String>>> nodesByWf =
        nodeRows.stream()
            .collect(
                Collectors.groupingBy(
                    r ->
                        normalize(r.get(COL_WORKFLOW_CODE))
                            + KEY_SEP_COLON
                            + normalize(r.get(COL_WORKFLOW_VERSION))));
    Map<String, List<Map<String, String>>> edgesByWf =
        edgeRows.stream()
            .collect(
                Collectors.groupingBy(
                    r ->
                        normalize(r.get(COL_WORKFLOW_CODE))
                            + KEY_SEP_COLON
                            + normalize(r.get(COL_WORKFLOW_VERSION))));
    int inserted = 0, updated = 0;
    for (Map<String, String> row : defRows) {
      String wfCode = normalize(row.get(COL_WORKFLOW_CODE));
      int version = Integer.parseInt(normalize(row.get(COL_VERSION)));
      WorkflowDefinitionEntity existing =
          workflowDefinitionMapper.selectByUniqueKey(ctx.tenantId(), wfCode, version);
      WorkflowDefinitionUpsertParam defParam = new WorkflowDefinitionUpsertParam();
      defParam.setTenantId(ctx.tenantId());
      defParam.setWorkflowCode(wfCode);
      defParam.setWorkflowName(normalize(row.get(COL_WORKFLOW_NAME)));
      defParam.setWorkflowType(normalizeEnum(row.get(COL_WORKFLOW_TYPE)));
      defParam.setVersion(version);
      defParam.setEnabled(parseBoolean(row.get(COL_ENABLED), true));
      defParam.setDescription(normalize(row.get(COL_DESCRIPTION)));
      defParam.setCreatedBy(safeOp(ctx.operatorId()));
      defParam.setUpdatedBy(safeOp(ctx.operatorId()));
      workflowDefinitionMapper.upsertWorkflowDefinition(defParam);
      if (existing == null) {
        inserted++;
      } else {
        updated++;
      }

      WorkflowDefinitionEntity saved =
          workflowDefinitionMapper.selectByUniqueKey(ctx.tenantId(), wfCode, version);
      if (saved == null || saved.getId() == null) {
        continue;
      }
      Long defId = saved.getId();
      String wfKey = wfCode + KEY_SEP_COLON + version;
      applyWfNodes(ctx.tenantId(), defId, nodesByWf.getOrDefault(wfKey, List.of()));
      applyWfEdges(ctx.tenantId(), defId, edgesByWf.getOrDefault(wfKey, List.of()));
    }
    return new ApplyStats(inserted, updated);
  }

  private void applyWfNodes(String tenantId, Long defId, List<Map<String, String>> nodes) {
    for (Map<String, String> node : nodes) {
      WorkflowNodeUpsertParam p = new WorkflowNodeUpsertParam();
      p.setTenantId(tenantId);
      p.setWorkflowDefinitionId(defId);
      p.setNodeCode(normalize(node.get(COL_NODE_CODE)));
      p.setNodeName(normalize(node.get(COL_NODE_NAME)));
      p.setNodeType(normalizeEnum(node.get(COL_NODE_TYPE)));
      p.setRelatedJobCode(normalize(node.get(COL_RELATED_JOB_CODE)));
      p.setRelatedPipelineCode(normalize(node.get(COL_RELATED_PIPELINE_CODE)));
      p.setWorkerGroup(CodeNormalizer.toUpperOrNull(node.get(COL_WORKER_GROUP)));
      p.setWindowCode(CodeNormalizer.toConfigFormOrNull(node.get(COL_WINDOW_CODE)));
      p.setNodeOrder(parseInteger(node.get(COL_NODE_ORDER)));
      p.setRetryPolicy(normalizeEnum(node.get(COL_RETRY_POLICY)));
      p.setRetryMaxCount(parseInteger(node.get(COL_RETRY_MAX_COUNT)));
      p.setTimeoutSeconds(parseInteger(node.get(COL_TIMEOUT_SECONDS)));
      p.setNodeParams(normalize(node.get(COL_NODE_PARAMS)));
      p.setEnabled(parseBoolean(node.get(COL_ENABLED), true));
      workflowNodeMapper.upsertWorkflowNode(p);
    }
  }

  private void applyWfEdges(String tenantId, Long defId, List<Map<String, String>> edges) {
    for (Map<String, String> edge : edges) {
      WorkflowEdgeUpsertParam p = new WorkflowEdgeUpsertParam();
      p.setTenantId(tenantId);
      p.setWorkflowDefinitionId(defId);
      p.setFromNodeCode(normalize(edge.get(COL_FROM_NODE_CODE)));
      p.setToNodeCode(normalize(edge.get(COL_TO_NODE_CODE)));
      p.setEdgeType(normalizeEnum(edge.get(COL_EDGE_TYPE)));
      p.setConditionExpr(normalize(edge.get(COL_CONDITION_EXPR)));
      p.setEnabled(parseBoolean(edge.get(COL_ENABLED), true));
      workflowEdgeMapper.upsertWorkflowEdge(p);
    }
  }

  private Map<String, Object> buildPipelineInsertParams(Map<String, String> row, ApplyContext ctx) {
    // PipelineDefinitionMapper.xml 的 insert/update 绑定是 snake_case（#{tenant_id} 等），
    // 这里 key 必须与之一致，否则 MyBatis 找不到变量 → 绑 null → 撞 NOT NULL 约束 500。
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("tenant_id", ctx.tenantId());
    p.put("job_code", normalize(row.get(COL_JOB_CODE)));
    p.put("pipeline_name", normalize(row.get(COL_PIPELINE_NAME)));
    p.put("pipeline_type", normalizeEnum(row.get(COL_PIPELINE_TYPE)));
    p.put("biz_type", normalize(row.get(COL_BIZ_TYPE)));
    p.put("worker_group", CodeNormalizer.toUpperOrNull(row.get(COL_WORKER_GROUP)));
    p.put(COL_VERSION, parseInteger(row.get(COL_VERSION)));
    p.put(COL_ENABLED, parseBoolean(row.get(COL_ENABLED), true));
    p.put(COL_DESCRIPTION, normalize(row.get(COL_DESCRIPTION)));
    p.put("created_by", safeOp(ctx.operatorId()));
    p.put("updated_by", safeOp(ctx.operatorId()));
    p.put(KEY_ID, null);
    return p;
  }

  private Map<String, Object> buildPipelineUpdateParams(
      Long id, Map<String, String> row, ApplyContext ctx) {
    Map<String, Object> p = buildPipelineInsertParams(row, ctx);
    p.put(KEY_ID, id);
    return p;
  }

  private Map<String, Object> buildStepInsertParams(Long pipelineId, Map<String, String> step) {
    // PipelineStepDefinitionMapper.xml 的 insert 绑定也是 snake_case
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("pipeline_definition_id", pipelineId);
    p.put("step_code", normalize(step.get(COL_STEP_CODE)));
    p.put("step_name", normalize(step.get(COL_STEP_NAME)));
    p.put("stage_code", normalizeEnum(step.get(COL_STAGE_CODE)));
    p.put("step_order", parseInteger(step.get("step_order")));
    p.put("impl_code", normalize(step.get("impl_code")));
    p.put("step_params", normalize(step.get("step_params")));
    p.put("timeout_seconds", parseInteger(step.get(COL_TIMEOUT_SECONDS)));
    p.put("retry_policy", normalizeEnum(step.get(COL_RETRY_POLICY)));
    p.put("retry_max_count", parseInteger(step.get(COL_RETRY_MAX_COUNT)));
    p.put(COL_ENABLED, parseBoolean(step.get(COL_ENABLED), true));
    return p;
  }

  private TenantConfigPackageExcelPreviewResponse toPreviewResponse(
      String uploadToken, String fileName, PackageValidationResult result) {
    List<SheetStats> sheets =
        List.of(
            toSheetStats(result.resourceQueues()),
            toSheetStats(result.businessCalendars()),
            toSheetStats(result.batchWindows()),
            toSheetStats(result.jobs()),
            toSheetStats(result.channels()),
            toSheetStats(result.fileTemplates()),
            toSheetStats(result.pipelines()),
            toSheetStats(result.steps()),
            toSheetStats(result.wfDefs()),
            toSheetStats(result.wfNodes()),
            toSheetStats(result.wfEdges()));
    List<IssueDto> issues =
        result.allIssues().stream()
            .map(i -> new IssueDto(i.sheetName(), i.rowNo(), i.columnName(), i.message()))
            .toList();
    int total = sheets.stream().mapToInt(SheetStats::totalRows).sum();
    int valid = sheets.stream().mapToInt(SheetStats::validRows).sum();
    return new TenantConfigPackageExcelPreviewResponse(
        uploadToken, fileName, total, valid, total - valid, sheets, issues);
  }

  private SheetStats toSheetStats(SheetResult r) {
    return new SheetStats(r.sheetName(), r.total(), r.valid(), r.invalid());
  }

  private PackageExcelSession loadSession(String uploadToken) {
    PackageExcelSession session =
        Guard.requireFound(importStore.get(uploadToken), "excel upload session not found");
    tenantGuard.assertTenantAllowed(session.tenantId());
    return session;
  }

  private static String normalize(String value) {
    return ConsoleTextSanitizer.normalize(value);
  }

  private static String normalizeEnum(String value) {
    String n = normalize(value);
    return n == null ? null : n.toUpperCase(Locale.ROOT);
  }

  private static Integer parseInteger(String value) {
    String n = normalize(value);
    if (!Texts.hasText(n)) {
      return null;
    }
    try {
      return Integer.parseInt(n);
    } catch (NumberFormatException e) {
      SwallowedExceptionLogger.info(
          DefaultConsoleTenantConfigPackageExcelApplicationService.class,
          "catch:NumberFormatException",
          e);

      return null;
    }
  }

  private static Boolean parseBoolean(String value, Boolean defaultValue) {
    String n = normalize(value);
    if (!Texts.hasText(n)) {
      return defaultValue;
    }
    String upper = n.toUpperCase(Locale.ROOT);
    if (Set.of("TRUE", "Y", "1", "YES").contains(upper)) {
      return true;
    }
    if (Set.of("FALSE", "N", "0", "NO").contains(upper)) {
      return false;
    }
    return defaultValue;
  }

  private static String safeOp(String operatorId) {
    return ConsoleTextSanitizer.safeInput(operatorId, 64);
  }

  private static Map<String, Integer> buildHeaderIndex(Row headerRow, DataFormatter fmt) {
    Map<String, Integer> index = new LinkedHashMap<>();
    for (int c = headerRow.getFirstCellNum(); c < headerRow.getLastCellNum(); c++) {
      String header = normalize(fmt.formatCellValue(headerRow.getCell(c)));
      if (Texts.hasText(header)) {
        index.put(header, c);
      }
    }
    return index;
  }

  private static void validateSheetHeaders(
      String sheetName, Map<String, Integer> headerIndex, Set<String> required) {
    List<String> missing = required.stream().filter(h -> !headerIndex.containsKey(h)).toList();
    if (!missing.isEmpty()) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "sheet [" + sheetName + "] missing required headers: " + missing);
    }
  }

  private static boolean isRowBlank(Row row, DataFormatter fmt) {
    for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
      if (Texts.hasText(fmt.formatCellValue(row.getCell(c)))) {
        return false;
      }
    }
    return true;
  }

  private static String cellText(Row row, Integer colIdx, DataFormatter fmt) {
    if (colIdx == null) {
      return null;
    }
    Cell cell = row.getCell(colIdx);
    return cell == null ? null : fmt.formatCellValue(cell);
  }

  private static String fileNameOrDefault(String originalFileName) {
    return Texts.hasText(originalFileName) ? originalFileName : "tenant-config-package.xlsx";
  }

  private record ApplyContext(String tenantId, String operatorId, String reason, String traceId) {}

  private record ApplyStats(int inserted, int updated) {}
}
