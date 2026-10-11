package io.github.pinpols.batch.console.infrastructure.config;

import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.*;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelWorkbookWriter.*;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.application.config.TenantConfigPackageExcelService;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigPackageExcelApplyRequest;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigPackageExcelApplyResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigPackageExcelGuideResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigPackageExcelPreviewResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigPackageExcelPreviewResponse.ErrorRowDto;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigPackageExcelPreviewResponse.IssueDto;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigPackageExcelPreviewResponse.SheetStats;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigPackageExcelUploadResponse;
import io.github.pinpols.batch.console.domain.file.mapper.FileChannelConfigMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.file.query.FileTemplateConfigQuery;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
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
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageSampleDataFactory;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageSheetSpecs;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageWorkbookLimits;
import io.github.pinpols.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport;
import io.github.pinpols.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import io.github.pinpols.batch.console.support.excel.ConsoleExcelStyles;
import io.github.pinpols.batch.console.support.excel.ConsoleSingleSheetExcelImportSupport;
import io.github.pinpols.batch.console.support.excel.TenantConfigPackageExcelImportStore;
import io.github.pinpols.batch.console.support.excel.TenantConfigPackageExcelImportStore.PackageExcelSession;
import io.github.pinpols.batch.console.support.excel.TenantConfigPackageExcelImportStore.PreviewSnapshot;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import io.github.pinpols.batch.console.support.web.UploadFileGuard;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFRelation;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.MessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 租户配置包 Excel 的全生命周期管理：export / template / upload → preview → apply。
 *
 * <p><b>3 阶段导入流程</b>：
 *
 * <ol>
 *   <li>{@link #upload} — 从临时文件解析 Excel（12 sheet），构建 {@code PackageExcelSession} 存入 {@link
 *       TenantConfigPackageExcelImportStore}，返回短期 token（内存 TTL）。
 *   <li>{@link #preview} — 用 token 取回 session，调 {@link ConfigPackageExcelValidator} 做 跨 sheet
 *       依赖校验（如 pipelineStep 引用的 jobCode 必须存在），返回每 sheet 的 valid/invalid 统计和逐行错误列表，不写库。
 *   <li>{@link #apply} — 再次 validate；若 {@code totalInvalid > 0} 直接拒绝；否则在单事务内 按 resourceQueue →
 *       businessCalendar → batchWindow → fileTemplate → channel → job → pipeline+step →
 *       workflow+node+edge 顺序写库， 完成后 {@code importStore.remove(token)}。
 * </ol>
 *
 * <p><b>12 sheets</b>：作业监控策略单独放在 {@code job_monitoring_policy}，与 {@code job_definition} 分开维护。
 * 该 sheet 可省略；缺失表示不修改已有监控策略，避免旧包回导清除当前设置。
 *
 * <p><b>多级结构写法</b>：
 *
 * <ul>
 *   <li>Pipeline：步骤按 {@code jobCode:version} 分组后与父行对应，apply 时先删再重插 step。
 *   <li>Workflow：节点和边按 {@code wfCode:version} 分组，upsert 节点/边（不删旧节点，依赖 Mapper 的 ON CONFLICT UPDATE
 *       语义）。
 * </ul>
 *
 * <p><b>会话安全</b>：{@link #loadSession} 每次访问都校验租户、上传操作人和权限集合。token 不能跨账号共享；授权集合变化后需重新上传，且各 Apply 业务权限仍由服务端独立校验。
 */
@Service
@RequiredArgsConstructor
@SuppressWarnings({"java:S2259", "java:S2583"})
public class DefaultTenantConfigPackageExcelService implements TenantConfigPackageExcelService {

  private static final String KEY_ID = "id";

  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final TenantConfigPackageExcelImportStore importStore;
  private final JdbcTemplate jdbcTemplate;
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
  private final TenantConfigPackageExcelApplyAuthorization applyAuthorization;

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

  private ConfigPackageExcelWorkbookWriter workbookWriter(List<String> scenarios) {
    return new ConfigPackageExcelWorkbookWriter(messageSource, scenarios);
  }

  @Override
  public ResponseEntity<StreamingResponseBody> exportPackage(String tenantId) {
    String tid = tenantGuard.resolveTenant(tenantId);
    boolean includePlatformFields = applyAuthorization.canViewPlatformFields();
    // R2-P1-9: 数据快照在请求线程里取（事务上下文），但 workbook 写入流的动作推迟到
    // StreamingResponseBody 阶段——Spring 在响应渲染时调用 lambda，避免堆里 buffer 整份 byte[]。
    List<Map<String, Object>> resourceQueues = includePlatformFields
        ? resourceQueueMapper.selectByQuery(tid, null, null, null, null)
        : List.of();
    List<Map<String, Object>> businessCalendars =
        withCalendarHolidayValues(businessCalendarMapper.selectByQuery(tid, null, null, null));
    List<Map<String, Object>> batchWindows = batchWindowMapper.selectByQuery(tid, null, null, null);
    List<JobDefinitionEntity> jobDefinitions =
        jobDefinitionMapper.selectByQuery(JobDefinitionQuery.ofTenant(tid, null));
    List<Map<String, Object>> jobs =
        rowProjections.toJobRows(jobDefinitions, includePlatformFields);
    List<Map<String, Object>> jobMonitoringPolicies =
        rowProjections.toJobMonitoringPolicyRows(jobDefinitions, includePlatformFields);
    List<Map<String, Object>> channels = rowProjections.toExportableChannelRows(
        fileChannelConfigMapper.selectByQuery(tid, null, null, null, null));
    List<Map<String, Object>> fileTemplates =
        fileTemplateConfigMapper.selectByQuery(FileTemplateConfigQuery.ofTenant(tid, null));
    List<Map<String, Object>> pipelines =
        pipelineDefinitionMapper.selectByQuery(tid, null, null, null, null);
    if (!includePlatformFields) {
      pipelines = rowProjections.withoutPlatformWorkerGroups(pipelines);
    }
    List<Map<String, Object>> steps = rowProjections.collectPipelineSteps(pipelines);
    List<WorkflowDefinitionEntity> wfEntities =
        workflowDefinitionMapper.selectByQuery(WorkflowDefinitionQuery.ofTenant(tid, null));
    List<Map<String, Object>> wfDefs = rowProjections.toWfDefRows(wfEntities);
    List<Map<String, Object>> wfNodes = rowProjections.collectWorkflowNodes(tid, wfEntities);
    if (!includePlatformFields) {
      wfNodes = rowProjections.withoutPlatformWorkerGroups(wfNodes);
    }
    List<Map<String, Object>> wfEdges = rowProjections.collectWorkflowEdges(tid, wfEntities);
    List<List<Map<String, Object>>> sheets = List.of(
        resourceQueues,
        businessCalendars,
        batchWindows,
        jobs,
        jobMonitoringPolicies,
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

  @Override
  public ResponseEntity<StreamingResponseBody> downloadScenarioTemplate(List<String> scenarios) {
    List<String> selectedScenarios =
        ConfigPackageSampleDataFactory.tenantTemplateScenarios(scenarios).stream()
            .sorted()
            .toList();
    ConfigPackageExcelWorkbookWriter writer = workbookWriter(selectedScenarios);
    Map<String, List<String>> implRegistry = loadRegisteredImplCodesByModule();
    String scenarioName = String.join("-", selectedScenarios).toLowerCase(Locale.ROOT);
    return ConsoleSingleSheetExcelImportSupport.excelStreamingResponse(
        "tenant-config-package-" + scenarioName + ".xlsx",
        out -> writer.writeFocusedTemplateWorkbook(out, implRegistry));
  }

  @Override
  public ResponseEntity<StreamingResponseBody> downloadSampleTemplate(
      String scenario, List<String> scenarios) {
    List<String> rawScenarios = EmptyChecks.isNotEmpty(scenarios)
        ? scenarios
        : EmptyChecks.isNotBlank(scenario) ? List.of(scenario) : List.of();
    List<String> normalizedScenarios =
        ConfigPackageSampleDataFactory.normalizeScenarios(rawScenarios).stream().toList();
    ConfigPackageExcelWorkbookWriter writer = workbookWriter(normalizedScenarios);
    Map<String, List<String>> implRegistry = loadRegisteredImplCodesByModule();
    List<List<Map<String, Object>>> sheets =
        ConfigPackageSampleDataFactory.sampleSheets(normalizedScenarios);
    String scenarioName = String.join("-", normalizedScenarios).toLowerCase(Locale.ROOT);
    String fileName = "tenant-config-package-sample-" + scenarioName + ".xlsx";
    return ConsoleSingleSheetExcelImportSupport.excelStreamingResponse(
        fileName, out -> writer.writeExportWorkbook(out, sheets, implRegistry));
  }

  @Override
  public TenantConfigPackageExcelGuideResponse guide() {
    List<TenantConfigPackageExcelGuideResponse.SheetGuide> sheets = new ArrayList<>();
    ConfigPackageSheetSpecs specs = new ConfigPackageSheetSpecs(messageSource);
    for (ConfigPackageSheetSpecs.SheetDef sheet : specs.build()) {
      List<TenantConfigPackageExcelGuideResponse.ColumnGuide> columns = new ArrayList<>();
      String sheetAppliesTo = appliesToFor(sheet.name(), "");
      for (String column : sheet.columns()) {
        ConsoleExcelStyles.ColumnGuide guide = sheet.guides().get(column);
        boolean required = EmptyChecks.isNotNull(guide) && guide.required();
        TenantConfigPackageExcelGuideResponse.ColumnGuide columnGuide =
            new TenantConfigPackageExcelGuideResponse.ColumnGuide(
                column,
                required,
                EmptyChecks.isNotNull(guide) && guide.readOnly(),
                guideLevelFor(sheet.name(), column, required),
                EmptyChecks.isNull(guide) ? "" : guide.formatHint(),
                EmptyChecks.isNull(guide) ? List.of() : guide.allowedValues(),
                EmptyChecks.isNull(guide) ? "" : guide.description(),
                EmptyChecks.isNull(guide) ? "" : guide.example(),
                fillExampleFor(sheet.name(), column, guide),
                defaultBehaviorFor(column, guide),
                appliesToFor(sheet.name(), column));
        columns.add(columnGuide);
      }
      sheets.add(new TenantConfigPackageExcelGuideResponse.SheetGuide(
          sheet.name(), sheetAppliesTo, columns));
    }
    return new TenantConfigPackageExcelGuideResponse(sheets);
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
          DefaultTenantConfigPackageExcelService.class, "catch:RuntimeException", ignored);

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
    String uploadedBy = requestMetadataResolver.current().operatorId();
    if (!Texts.hasText(uploadedBy)) {
      throw BizException.of(ResultCode.FORBIDDEN, "error.config_package.session_actor_required");
    }
    PackageExcelSession session;
    Path temporaryWorkbook = Files.createTempFile("tenant-config-package-", ".xlsx");
    try {
      file.transferTo(temporaryWorkbook);
      session = parseWorkbook(temporaryWorkbook, tenantId, fileName)
          .withUploadContext(uploadedBy, currentPermissionScope());
    } finally {
      Files.deleteIfExists(temporaryWorkbook);
    }
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
    // 先校验上传会话归属再轮换一次性令牌，避免未授权请求使上传者当前预览失效。
    PackageExcelSession currentSession = loadSession(uploadToken);
    long configurationRevision = currentConfigurationRevision(currentSession.tenantId());
    PreviewSnapshot snapshot = importStore.issuePreviewSnapshot(uploadToken, configurationRevision);
    if (EmptyChecks.isNull(snapshot)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.config_package.apply_in_progress");
    }
    PackageExcelSession session = snapshot.session();
    tenantGuard.assertTenantAllowed(session.tenantId());
    assertSessionOwner(session);
    PackageValidationResult result = validator().validate(session);
    return toPreviewResponse(uploadToken, snapshot.previewToken(), session, result);
  }

  /**
   * 内联编辑回写:把出错行被改动的单元格合并进 session 对应行（{@code rowNo - 2} = 列表下标），再重校验并返回新预览。 不落库——仍走原 apply
   * 闸门（invalid > 0 拒绝）。会话仍按 token 持有,30 分钟 TTL。
   */
  @Override
  public TenantConfigPackageExcelPreviewResponse patchRow(
      String uploadToken,
      String previewToken,
      String sheetName,
      int rowNo,
      Map<String, String> values) {
    PackageExcelSession session = loadSession(uploadToken);
    requireNotApplying(uploadToken);
    PackageExcelSession patched = copySession(session);
    List<Map<String, String>> rows = sheetRowsByName(patched).get(sheetName);
    Guard.require(rows != null, "unknown sheet: " + sheetName);
    Guard.require(rowNo >= 2, "row out of range: " + rowNo);
    int idx = rowNo - 2;
    Guard.require(idx >= 0 && idx < rows.size(), "row out of range: " + rowNo);
    Map<String, String> target = rows.get(idx);
    if (values != null) {
      values.forEach((k, v) -> {
        if (target.containsKey(k)) {
          target.put(k, v == null ? "" : v.trim());
        }
      });
    }
    PackageValidationResult result = validator().validate(patched);
    String nextPreviewToken = UUID.randomUUID().toString().replace("-", "");
    long configurationRevision = currentConfigurationRevision(session.tenantId());
    if (!importStore.replaceSession(
        uploadToken, previewToken, patched, nextPreviewToken, configurationRevision)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.config_package.preview_stale");
    }
    return toPreviewResponse(uploadToken, nextPreviewToken, patched, result);
  }

  private static PackageExcelSession copySession(PackageExcelSession session) {
    return new PackageExcelSession(
        session.fileName(),
        session.tenantId(),
        session.uploadedAt(),
        copyRows(session.resourceQueueRows()),
        copyRows(session.businessCalendarRows()),
        copyRows(session.batchWindowRows()),
        copyRows(session.jobRows()),
        copyRows(session.jobMonitoringPolicyRows()),
        copyRows(session.fileChannelRows()),
        copyRows(session.fileTemplateRows()),
        copyRows(session.pipelineRows()),
        copyRows(session.pipelineStepRows()),
        copyRows(session.workflowDefinitionRows()),
        copyRows(session.workflowNodeRows()),
        copyRows(session.workflowEdgeRows()),
        session.uploadedBy(),
        session.permissionScope());
  }

  private static List<Map<String, String>> copyRows(List<Map<String, String>> rows) {
    return rows.stream().<Map<String, String>>map(LinkedHashMap::new).toList();
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
    String applyOwnerToken = UUID.randomUUID().toString().replace("-", "");
    if (!importStore.claimForApply(uploadToken, request.getPreviewToken(), applyOwnerToken)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.config_package.preview_stale");
    }
    boolean completionRegistered = false;
    try {
      long expectedRevision =
          importStore.configurationRevisionForApply(uploadToken, applyOwnerToken);
      long actualRevision = lockConfigurationRevision(session.tenantId());
      if (expectedRevision != actualRevision) {
        throw BizException.of(ResultCode.STATE_CONFLICT, "error.config_package.config_changed");
      }
      PackageValidationResult result = validator().validate(session);
      if (result.totalInvalid() > 0) {
        throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.excel.invalid_rows");
      }
      applyAuthorization.assertAllowed(result);
      ConsoleRequestMetadata metadata = requestMetadataResolver.current();
      TenantConfigPackageExcelApplyService.ApplyContext ctx =
          new TenantConfigPackageExcelApplyService.ApplyContext(
              session.tenantId(), metadata.operatorId(), request.getReason(), metadata.traceId());

      TenantConfigPackageExcelApplyService.ApplyCounts counts = applyService.applyAll(result, ctx);
      registerApplyCompletion(uploadToken, applyOwnerToken);
      completionRegistered = true;
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
    } finally {
      if (!completionRegistered) {
        importStore.releaseApply(uploadToken, applyOwnerToken);
      }
    }
  }

  private long currentConfigurationRevision(String tenantId) {
    Long revision = jdbcTemplate.queryForObject(
        "select batch.current_tenant_configuration_revision(?)", Long.class, tenantId);
    if (EmptyChecks.isNull(revision)) {
      throw new IllegalStateException("tenant configuration revision is missing");
    }
    return revision;
  }

  private long lockConfigurationRevision(String tenantId) {
    Long revision = jdbcTemplate.queryForObject(
        "select batch.lock_tenant_configuration_revision(?)", Long.class, tenantId);
    if (EmptyChecks.isNull(revision)) {
      throw new IllegalStateException("tenant configuration revision is missing");
    }
    return revision;
  }

  private void registerApplyCompletion(String uploadToken, String applyOwnerToken) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "configuration package apply requires transaction synchronization");
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        importStore.completeApply(uploadToken, applyOwnerToken);
      }

      @Override
      public void afterCompletion(int status) {
        importStore.releaseApply(uploadToken, applyOwnerToken);
      }
    });
  }

  private void requireNotApplying(String uploadToken) {
    if (importStore.isApplyInProgress(uploadToken)) {
      throw BizException.of(ResultCode.CONFLICT, "error.config_package.apply_in_progress");
    }
  }

  private PackageExcelSession parseWorkbook(Path workbookPath, String tenantId, String fileName) {
    ConfigPackageWorkbookLimits.configurePoiZipLimits();
    try (Workbook wb = WorkbookFactory.create(workbookPath.toFile())) {
      ConfigPackageWorkbookLimits.validate(wb);
      rejectExternalWorkbookFeatures(wb);
      rejectFormulaCells(wb);
      validateWorkbookSheetNames(wb);
      return new PackageExcelSession(
          fileName,
          tenantId,
          dateTimeSupport.nowInstant(),
          parseOptionalSheet(wb, RESOURCE_QUEUE_SHEET, RESOURCE_QUEUE_COLUMNS, tenantId),
          parseOptionalSheet(wb, BUSINESS_CALENDAR_SHEET, BUSINESS_CALENDAR_COLUMNS, tenantId),
          parseOptionalSheet(wb, BATCH_WINDOW_SHEET, BATCH_WINDOW_COLUMNS, tenantId),
          parseOptionalSheet(wb, JOB_SHEET, JOB_COLUMNS, tenantId),
          parseOptionalSheet(
              wb, JOB_MONITORING_POLICY_SHEET, JOB_MONITORING_POLICY_COLUMNS, tenantId),
          parseOptionalSheet(wb, CHANNEL_SHEET, CHANNEL_COLUMNS, tenantId),
          parseOptionalSheet(wb, FILE_TEMPLATE_SHEET, FILE_TEMPLATE_COLUMNS, tenantId),
          parseOptionalSheet(wb, PIPELINE_SHEET, PIPELINE_COLUMNS, tenantId),
          parseOptionalSheet(wb, STEP_SHEET, STEP_COLUMNS, null),
          parseOptionalSheet(wb, WF_DEF_SHEET, WF_DEF_COLUMNS, tenantId),
          parseOptionalSheet(wb, WF_NODE_SHEET, WF_NODE_COLUMNS, tenantId),
          parseOptionalSheet(wb, WF_EDGE_SHEET, WF_EDGE_COLUMNS, tenantId));
    } catch (BizException e) {
      throw e;
    } catch (Exception e) {
      SwallowedExceptionLogger.info(
          DefaultTenantConfigPackageExcelService.class, "config-package-workbook-rejected", e);
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ResultCode.INVALID_ARGUMENT.detailKey(),
          "failed to read excel workbook");
    }
  }

  private static void rejectExternalWorkbookFeatures(Workbook workbook)
      throws InvalidFormatException {
    if (!(workbook instanceof XSSFWorkbook xssf)) {
      return;
    }
    boolean hasExternalLinks = xssf.getPackagePart()
        .getRelationshipsByType(XSSFRelation.EXTERNAL_LINKS.getRelation())
        .iterator()
        .hasNext();
    boolean hasVbaProject = xssf.getPackagePart()
        .getRelationshipsByType(XSSFRelation.VBA_MACROS.getRelation())
        .iterator()
        .hasNext();
    if (hasExternalLinks || hasVbaProject) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ResultCode.INVALID_ARGUMENT.detailKey(),
          "external links and macros are not allowed in configuration packages");
    }
  }

  private static void rejectFormulaCells(Workbook workbook) {
    for (Sheet sheet : workbook) {
      for (Row row : sheet) {
        for (org.apache.poi.ss.usermodel.Cell cell : row) {
          if (cell.getCellType() == CellType.FORMULA) {
            throw BizException.of(
                ResultCode.INVALID_ARGUMENT,
                ResultCode.INVALID_ARGUMENT.detailKey(),
                "formula cells are not allowed in configuration packages");
          }
        }
      }
    }
  }

  private static void validateWorkbookSheetNames(Workbook workbook) {
    Set<String> accepted = Set.of(
        RESOURCE_QUEUE_SHEET,
        BUSINESS_CALENDAR_SHEET,
        BATCH_WINDOW_SHEET,
        JOB_SHEET,
        JOB_MONITORING_POLICY_SHEET,
        CHANNEL_SHEET,
        FILE_TEMPLATE_SHEET,
        PIPELINE_SHEET,
        STEP_SHEET,
        WF_DEF_SHEET,
        WF_NODE_SHEET,
        WF_EDGE_SHEET,
        ConsoleExcelStyles.SHEET_NAME_README,
        "填写顺序",
        "依赖说明",
        "五类Worker示例",
        "文件束示例",
        ConsoleExcelStyles.SHEET_NAME_FIELD_GUIDE,
        ConsoleExcelStyles.SHEET_NAME_VALIDATION,
        ConsoleExcelStyles.SHEET_NAME_DICT);
    for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
      String name = workbook.getSheetName(i);
      Guard.require(accepted.contains(name), "unsupported excel sheet: " + name);
    }
  }

  private List<Map<String, String>> parseSheet(
      Workbook wb, String sheetName, List<String> columns, String tenantId) {
    Sheet sheet = wb.getSheet(sheetName);
    Guard.require(sheet != null, "excel sheet missing: " + sheetName);
    DataFormatter fmt = new DataFormatter();
    Row headerRow = sheet.getRow(sheet.getFirstRowNum());
    Guard.require(headerRow != null, "header row missing in sheet: " + sheetName);
    Map<String, Integer> headerIndex = buildHeaderIndex(sheetName, headerRow, fmt);
    validateSheetHeaders(
        sheetName, headerIndex, requiredHeaders(sheetName, columns), Set.copyOf(columns));
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
    return ConfigPackageSheetSpecs.requiredImportHeaders(sheetName, columns);
  }

  private List<Map<String, String>> parseOptionalSheet(
      Workbook wb, String sheetName, List<String> columns, String tenantId) {
    return wb.getSheet(sheetName) == null
        ? List.of()
        : parseSheet(wb, sheetName, columns, tenantId);
  }

  private TenantConfigPackageExcelPreviewResponse toPreviewResponse(
      String uploadToken,
      String previewToken,
      PackageExcelSession session,
      PackageValidationResult result) {
    Map<String, Set<Integer>> crossReferenceInvalidRows = result.crossRefIssues().stream()
        .filter(issue -> issue.rowNo() >= 2)
        .collect(Collectors.groupingBy(
            WorkbookIssue::sheetName,
            Collectors.mapping(WorkbookIssue::rowNo, Collectors.toSet())));
    List<SheetStats> sheets = List.of(
        toSheetStats(result.resourceQueues(), crossReferenceInvalidRows),
        toSheetStats(result.businessCalendars(), crossReferenceInvalidRows),
        toSheetStats(result.batchWindows(), crossReferenceInvalidRows),
        toSheetStats(result.jobs(), crossReferenceInvalidRows),
        toSheetStats(result.jobMonitoringPolicies(), crossReferenceInvalidRows),
        toSheetStats(result.channels(), crossReferenceInvalidRows),
        toSheetStats(result.fileTemplates(), crossReferenceInvalidRows),
        toSheetStats(result.pipelines(), crossReferenceInvalidRows),
        toSheetStats(result.steps(), crossReferenceInvalidRows),
        toSheetStats(result.wfDefs(), crossReferenceInvalidRows),
        toSheetStats(result.wfNodes(), crossReferenceInvalidRows),
        toSheetStats(result.wfEdges(), crossReferenceInvalidRows));
    List<IssueDto> issues = result.allIssues().stream()
        .map(i -> new IssueDto(i.sheetName(), i.rowNo(), i.columnName(), i.message()))
        .toList();
    int total = sheets.stream().mapToInt(SheetStats::totalRows).sum();
    int valid = sheets.stream().mapToInt(SheetStats::validRows).sum();
    return new TenantConfigPackageExcelPreviewResponse(
        uploadToken,
        previewToken,
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
    m.put(JOB_MONITORING_POLICY_SHEET, session.jobMonitoringPolicyRows());
    m.put(CHANNEL_SHEET, session.fileChannelRows());
    m.put(FILE_TEMPLATE_SHEET, session.fileTemplateRows());
    m.put(PIPELINE_SHEET, session.pipelineRows());
    m.put(STEP_SHEET, session.pipelineStepRows());
    m.put(WF_DEF_SHEET, session.workflowDefinitionRows());
    m.put(WF_NODE_SHEET, session.workflowNodeRows());
    m.put(WF_EDGE_SHEET, session.workflowEdgeRows());
    return m;
  }

  private SheetStats toSheetStats(
      SheetResult result, Map<String, Set<Integer>> crossReferenceInvalidRows) {
    int crossReferenceInvalidCount =
        crossReferenceInvalidRows.getOrDefault(result.sheetName(), Set.of()).size();
    int invalidRows = Math.min(result.total(), result.invalid() + crossReferenceInvalidCount);
    return new SheetStats(
        result.sheetName(), result.total(), result.total() - invalidRows, invalidRows);
  }

  private PackageExcelSession loadSession(String uploadToken) {
    PackageExcelSession session =
        Guard.requireFound(importStore.get(uploadToken), "excel upload session not found");
    tenantGuard.assertTenantAllowed(session.tenantId());
    assertSessionOwner(session);
    return session;
  }

  private void assertSessionOwner(PackageExcelSession session) {
    if (EmptyChecks.isNull(session.uploadedBy())) {
      return;
    }
    ConsoleRequestMetadata metadata = requestMetadataResolver.current();
    if (!session.uploadedBy().equals(metadata.operatorId())
        || !session.permissionScope().equals(currentPermissionScope())) {
      throw BizException.of(ResultCode.FORBIDDEN, "error.config_package.session_owner_mismatch");
    }
  }

  private static Set<String> currentPermissionScope() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (EmptyChecks.isNull(authentication) || !authentication.isAuthenticated()) {
      return Set.of();
    }
    return authentication.getAuthorities().stream()
        .map(authority -> authority.getAuthority())
        .collect(Collectors.toUnmodifiableSet());
  }

  private static Map<String, Integer> buildHeaderIndex(
      String sheetName, Row headerRow, DataFormatter fmt) {
    Map<String, Integer> index = new LinkedHashMap<>();
    for (int c = headerRow.getFirstCellNum(); c < headerRow.getLastCellNum(); c++) {
      String header =
          TenantConfigPackageExcelValueSupport.normalize(fmt.formatCellValue(headerRow.getCell(c)));
      if (Texts.hasText(header)) {
        if (EmptyChecks.isNotNull(index.putIfAbsent(header, c))) {
          throw BizException.of(
              ResultCode.INVALID_ARGUMENT,
              ResultCode.INVALID_ARGUMENT.detailKey(),
              "sheet [" + sheetName + "] contains duplicate headers: " + header);
        }
      }
    }
    return index;
  }

  private static void validateSheetHeaders(
      String sheetName,
      Map<String, Integer> headerIndex,
      Set<String> required,
      Set<String> supported) {
    List<String> missing =
        required.stream().filter(h -> !headerIndex.containsKey(h)).toList();
    if (!missing.isEmpty()) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ResultCode.INVALID_ARGUMENT.detailKey(),
          "sheet [" + sheetName + "] missing required headers: " + missing);
    }
    List<String> unknown = headerIndex.keySet().stream()
        .filter(header -> !supported.contains(header))
        .toList();
    if (EmptyChecks.isNotEmpty(unknown)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ResultCode.INVALID_ARGUMENT.detailKey(),
          "sheet [" + sheetName + "] contains unsupported headers: " + unknown);
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
