package io.github.pinpols.batch.console.infrastructure.config;

import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.*;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelWorkbookWriter.*;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigPackageExcelApplicationService;
import io.github.pinpols.batch.console.domain.file.mapper.FileChannelConfigMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.file.query.FileTemplateConfigQuery;
import io.github.pinpols.batch.console.domain.job.mapper.BatchWindowMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.CalendarHolidayMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.mapper.StepRegistryQueryMapper;
import io.github.pinpols.batch.console.domain.job.query.JobDefinitionQuery;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.PipelineDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowDefinitionQuery;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.PackageValidationResult;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.SheetResult;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelWorkbookWriter;
import io.github.pinpols.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport;
import io.github.pinpols.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import io.github.pinpols.batch.console.support.excel.ConsoleSingleSheetExcelImportSupport;
import io.github.pinpols.batch.console.support.excel.TenantConfigPackageExcelImportStore;
import io.github.pinpols.batch.console.support.excel.TenantConfigPackageExcelImportStore.PackageExcelSession;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import io.github.pinpols.batch.console.support.web.UploadFileGuard;
import io.github.pinpols.batch.console.web.request.config.TenantConfigPackageExcelApplyRequest;
import io.github.pinpols.batch.console.web.response.config.TenantConfigPackageExcelApplyResponse;
import io.github.pinpols.batch.console.web.response.config.TenantConfigPackageExcelPreviewResponse;
import io.github.pinpols.batch.console.web.response.config.TenantConfigPackageExcelPreviewResponse.ErrorRowDto;
import io.github.pinpols.batch.console.web.response.config.TenantConfigPackageExcelPreviewResponse.IssueDto;
import io.github.pinpols.batch.console.web.response.config.TenantConfigPackageExcelPreviewResponse.SheetStats;
import io.github.pinpols.batch.console.web.response.config.TenantConfigPackageExcelUploadResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
@SuppressWarnings({"java:S2259", "java:S2583"})
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
  private final WorkflowDefinitionMapper workflowDefinitionMapper;
  private final StepRegistryQueryMapper stepRegistryQueryMapper;
  private final TenantConfigPackageRowProjections rowProjections;
  private final BatchDateTimeSupport dateTimeSupport;
  private final MessageSource messageSource;
  private final TenantConfigPackageExcelApplyService applyService;

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
    List<Map<String, Object>> jobs = rowProjections.toJobRows(
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
    List<List<Map<String, Object>>> sheets = List.of(
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
      out.add(ConfigPackageExcelSchema.BusinessCalendar.toExportRow(
          row, calendarHolidaysText(row.get(KEY_ID))));
    }
    return out;
  }

  private String calendarHolidaysText(Object idValue) {
    if (!(idValue instanceof Number number)) {
      return null;
    }
    List<String> dates = calendarHolidayMapper.selectByCalendarId(number.longValue()).stream()
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
    return toPreviewResponse(uploadToken, session, result);
  }

  /**
   * 内联编辑回写:把出错行被改动的单元格合并进 session 对应行（{@code rowNo - 2} = 列表下标），再重校验并返回新预览。 不落库——仍走原 apply
   * 闸门（invalid > 0 拒绝）。会话仍按 token 持有,30 分钟 TTL。
   */
  @Override
  public TenantConfigPackageExcelPreviewResponse patchRow(
      String uploadToken, String sheetName, int rowNo, Map<String, String> values) {
    PackageExcelSession session = loadSession(uploadToken);
    List<Map<String, String>> rows = sheetRowsByName(session).get(sheetName);
    Guard.require(rows != null, "unknown sheet: " + sheetName);
    Guard.require(rowNo >= 2, "row out of range: " + rowNo);
    int idx = rowNo - 2;
    Guard.require(idx >= 0 && idx < rows.size(), "row out of range: " + rowNo);
    Map<String, String> target = rows.get(idx);
    if (values != null) {
      // 只合并该行已有的列键,挡掉前端传错列名凭空塞键;value 走与解析期一致的 normalize(trim)
      values.forEach((k, v) -> {
        if (target.containsKey(k)) {
          target.put(k, v == null ? "" : v.trim());
        }
      });
    }
    PackageValidationResult result = validator().validate(session);
    return toPreviewResponse(uploadToken, session, result);
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
    TenantConfigPackageExcelApplyService.ApplyContext ctx =
        new TenantConfigPackageExcelApplyService.ApplyContext(
            session.tenantId(), metadata.operatorId(), request.getReason(), metadata.traceId());

    TenantConfigPackageExcelApplyService.ApplyCounts counts = applyService.applyAll(result, ctx);

    importStore.remove(uploadToken);
    return new TenantConfigPackageExcelApplyResponse(
        uploadToken,
        session.tenantId(),
        counts.resourceQueueInserted(),
        counts.resourceQueueUpdated(),
        counts.businessCalendarInserted(),
        counts.businessCalendarUpdated(),
        counts.batchWindowInserted(),
        counts.batchWindowUpdated(),
        counts.jobInserted(),
        counts.jobUpdated(),
        counts.channelInserted(),
        counts.channelUpdated(),
        counts.fileTemplateInserted(),
        counts.fileTemplateUpdated(),
        counts.pipelineInserted(),
        counts.pipelineUpdated(),
        counts.workflowInserted(),
        counts.workflowUpdated());
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
    validateSheetHeaders(sheetName, headerIndex, requiredHeaders(sheetName, columns));
    List<Map<String, String>> rows = new ArrayList<>();
    for (int i = headerRow.getRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
      Row row = sheet.getRow(i);
      if (row == null || isRowBlank(row, fmt)) {
        continue;
      }
      Map<String, String> values = new LinkedHashMap<>();
      for (String col : columns) {
        Integer colIdx = headerIndex.get(col);
        if (colIdx == null) {
          continue;
        }
        values.put(col, TenantConfigPackageExcelValueSupport.normalize(cellText(row, colIdx, fmt)));
      }
      if (tenantId != null && !Texts.hasText(values.get(COL_TENANT_ID))) {
        values.put(COL_TENANT_ID, tenantId);
      }
      rows.add(values);
    }
    return rows;
  }

  private static Set<String> requiredHeaders(String sheetName, List<String> columns) {
    if (!JOB_SHEET.equals(sheetName)) {
      return Set.copyOf(columns);
    }
    Set<String> required = new LinkedHashSet<>(columns);
    required.remove(COL_DEPENDS_ON_JOB_CODE);
    return required;
  }

  private List<Map<String, String>> parseOptionalSheet(
      Workbook wb, String sheetName, List<String> columns, String tenantId) {
    return wb.getSheet(sheetName) == null
        ? List.of()
        : parseSheet(wb, sheetName, columns, tenantId);
  }

  private TenantConfigPackageExcelPreviewResponse toPreviewResponse(
      String uploadToken, PackageExcelSession session, PackageValidationResult result) {
    List<SheetStats> sheets = List.of(
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
    List<IssueDto> issues = result.allIssues().stream()
        .map(i -> new IssueDto(i.sheetName(), i.rowNo(), i.columnName(), i.message()))
        .toList();
    int total = sheets.stream().mapToInt(SheetStats::totalRows).sum();
    int valid = sheets.stream().mapToInt(SheetStats::validRows).sum();
    return new TenantConfigPackageExcelPreviewResponse(
        uploadToken,
        session.fileName(),
        total,
        valid,
        total - valid,
        sheets,
        issues,
        toErrorRows(session, result));
  }

  /** 按 (sheet, rowNo) 聚合出错行 + 该行整行单元格值,供前端内联编辑。保持 issue 出现顺序。 */
  private List<ErrorRowDto> toErrorRows(
      PackageExcelSession session, PackageValidationResult result) {
    Map<String, List<Map<String, String>>> rowsBySheet = sheetRowsByName(session);
    Map<String, ErrorRowAccumulator> grouped = new LinkedHashMap<>();
    for (WorkbookIssue issue : result.allIssues()) {
      String key = issue.sheetName() + "#" + issue.rowNo();
      grouped
          .computeIfAbsent(
              key,
              k -> new ErrorRowAccumulator(issue.sheetName(), issue.rowNo(), new ArrayList<>()))
          .messages()
          .add(issue.message());
    }
    List<ErrorRowDto> out = new ArrayList<>(grouped.size());
    for (ErrorRowAccumulator acc : grouped.values()) {
      Map<String, String> values = Map.of();
      List<Map<String, String>> rows = rowsBySheet.get(acc.sheetName());
      int idx = acc.rowNo() - 2;
      if (rows != null && idx >= 0 && idx < rows.size()) {
        values = new LinkedHashMap<>(rows.get(idx));
      }
      out.add(new ErrorRowDto(acc.sheetName(), acc.rowNo(), values, List.copyOf(acc.messages())));
    }
    return out;
  }

  private record ErrorRowAccumulator(String sheetName, int rowNo, List<String> messages) {}

  /** sheet 名(validator SHEET 常量)→ session 对应行列表。内联编辑 patch 与出错行回填共用。 */
  private Map<String, List<Map<String, String>>> sheetRowsByName(PackageExcelSession session) {
    Map<String, List<Map<String, String>>> m = new LinkedHashMap<>();
    m.put(RESOURCE_QUEUE_SHEET, session.resourceQueueRows());
    m.put(BUSINESS_CALENDAR_SHEET, session.businessCalendarRows());
    m.put(BATCH_WINDOW_SHEET, session.batchWindowRows());
    m.put(JOB_SHEET, session.jobRows());
    m.put(CHANNEL_SHEET, session.fileChannelRows());
    m.put(FILE_TEMPLATE_SHEET, session.fileTemplateRows());
    m.put(PIPELINE_SHEET, session.pipelineRows());
    m.put(STEP_SHEET, session.pipelineStepRows());
    m.put(WF_DEF_SHEET, session.workflowDefinitionRows());
    m.put(WF_NODE_SHEET, session.workflowNodeRows());
    m.put(WF_EDGE_SHEET, session.workflowEdgeRows());
    return m;
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

  private static Map<String, Integer> buildHeaderIndex(Row headerRow, DataFormatter fmt) {
    Map<String, Integer> index = new LinkedHashMap<>();
    for (int c = headerRow.getFirstCellNum(); c < headerRow.getLastCellNum(); c++) {
      String header =
          TenantConfigPackageExcelValueSupport.normalize(fmt.formatCellValue(headerRow.getCell(c)));
      if (Texts.hasText(header)) {
        index.put(header, c);
      }
    }
    return index;
  }

  private static void validateSheetHeaders(
      String sheetName, Map<String, Integer> headerIndex, Set<String> required) {
    List<String> missing =
        required.stream().filter(h -> !headerIndex.containsKey(h)).toList();
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
}
