package io.github.pinpols.batch.console.infrastructure.excel;

import io.github.pinpols.batch.common.enums.DictEnum;
import io.github.pinpols.batch.common.enums.ExecutionMode;
import io.github.pinpols.batch.common.enums.FileChannelAuthType;
import io.github.pinpols.batch.common.enums.FileChannelType;
import io.github.pinpols.batch.common.enums.FileReceiptPolicy;
import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.enums.PipelineType;
import io.github.pinpols.batch.common.enums.RetryPolicyType;
import io.github.pinpols.batch.common.enums.ShardStrategy;
import io.github.pinpols.batch.common.enums.WorkflowEdgeType;
import io.github.pinpols.batch.common.enums.WorkflowNodeType;
import io.github.pinpols.batch.common.enums.WorkflowType;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.persistence.BatchColumnNames;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BatchWindowMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.mapper.StepRegistryQueryMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.PipelineDefinitionMapper;
import io.github.pinpols.batch.console.infrastructure.excel.BatchWindowExcelRowParser.WindowRow;
import io.github.pinpols.batch.console.infrastructure.excel.BusinessCalendarExcelRowParser.CalendarRow;
import io.github.pinpols.batch.console.infrastructure.excel.FileTemplateExcelRowParser.TemplateRow;
import io.github.pinpols.batch.console.infrastructure.excel.ResourceQueueExcelRowParser.QueueRow;
import io.github.pinpols.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import io.github.pinpols.batch.console.support.excel.TenantConfigPackageExcelImportStore.PackageExcelSession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 校验从租户配置包 Excel 工作簿解析出的行。从 DefaultConsoleTenantConfigPackageExcelApplicationService 抽出以缩减类体积。 */
@SuppressWarnings("java:S2583")
public class ConfigPackageExcelValidator {

  public static final String COL_TENANT_ID = BatchColumnNames.TENANT_ID;
  public static final String COL_ENABLED = "enabled";
  public static final String COL_DESCRIPTION = "description";
  public static final String COL_VERSION = "version";
  public static final String COL_BIZ_TYPE = "biz_type";
  public static final String COL_WORKER_GROUP = "worker_group";
  public static final String COL_WINDOW_CODE = "window_code";
  public static final String COL_RETRY_POLICY = "retry_policy";
  public static final String COL_RETRY_MAX_COUNT = "retry_max_count";
  public static final String COL_TIMEOUT_SECONDS = "timeout_seconds";
  public static final String COL_SHARD_STRATEGY = "shard_strategy";
  public static final String COL_EXECUTION_MODE = "execution_mode";
  public static final String COL_WATERMARK_FIELD = "watermark_field";
  public static final String COL_JOB_CODE = "job_code";
  public static final String COL_JOB_NAME = "job_name";
  public static final String COL_JOB_TYPE = "job_type";
  public static final String COL_SCHEDULE_TYPE = "schedule_type";
  public static final String COL_SCHEDULE_EXPR = "schedule_expr";
  public static final String COL_DEPENDS_ON_JOB_CODE = "depends_on_job_code";
  public static final String COL_CALENDAR_CODE = "calendar_code";
  public static final String COL_QUEUE_CODE = "queue_code";
  public static final String COL_PARAM_SCHEMA = "param_schema";
  public static final String COL_CHANNEL_TYPE = "channel_type";
  public static final String COL_AUTH_TYPE = "auth_type";
  public static final String COL_RECEIPT_POLICY = "receipt_policy";
  public static final String COL_SEVERITY = "severity";
  public static final String COL_PIPELINE_TYPE = "pipeline_type";
  public static final String COL_STAGE_CODE = "stage_code";
  public static final String COL_WORKFLOW_CODE = "workflow_code";
  public static final String COL_WORKFLOW_NAME = "workflow_name";
  public static final String COL_WORKFLOW_TYPE = "workflow_type";
  public static final String COL_WORKFLOW_VERSION = "workflow_version";
  public static final String COL_NODE_CODE = "node_code";
  public static final String COL_NODE_NAME = "node_name";
  public static final String COL_NODE_TYPE = "node_type";
  public static final String COL_RELATED_JOB_CODE = "related_job_code";
  public static final String COL_RELATED_PIPELINE_CODE = "related_pipeline_code";
  public static final String COL_NODE_PARAMS = "node_params";
  public static final String COL_EDGE_TYPE = "edge_type";
  public static final String COL_FROM_NODE_CODE = "from_node_code";
  public static final String COL_TO_NODE_CODE = "to_node_code";
  public static final String COL_EXECUTION_HANDLER = "execution_handler";
  public static final String COL_DEFAULT_PARAMS = "default_params";
  public static final String COL_CHANNEL_CODE = "channel_code";
  public static final String COL_CHANNEL_NAME = "channel_name";
  public static final String COL_CONFIG_JSON = "config_json";
  public static final String COL_PIPELINE_NAME = "pipeline_name";
  public static final String COL_STEP_CODE = "step_code";
  public static final String COL_STEP_NAME = "step_name";
  public static final String COL_IMPL_CODE = "impl_code";
  public static final String COL_NODE_ORDER = "node_order";
  public static final String COL_CONDITION_EXPR = "condition_expr";

  public static final String KEY_SEP_COLON = ":";
  public static final String KEY_SEP_HASH = "#";
  private static final String INTERNAL_ROW_NO = "__excel_row_no";

  public static final String JOB_SHEET = "job_definition";
  public static final String RESOURCE_QUEUE_SHEET = ResourceQueueExcelRowParser.SHEET_NAME;
  public static final String BUSINESS_CALENDAR_SHEET = BusinessCalendarExcelRowParser.SHEET_NAME;
  public static final String BATCH_WINDOW_SHEET = BatchWindowExcelRowParser.SHEET_NAME;
  public static final String CHANNEL_SHEET = "file_channel_config";
  public static final String FILE_TEMPLATE_SHEET = FileTemplateExcelRowParser.SHEET_NAME;
  public static final String PIPELINE_SHEET = "pipeline_definition";
  public static final String STEP_SHEET = "pipeline_step_definition";
  public static final String WF_DEF_SHEET = "workflow_definition";
  public static final String WF_NODE_SHEET = "workflow_node";
  public static final String WF_EDGE_SHEET = "workflow_edge";

  public static final Set<String> JOB_TYPES = DictEnum.codes(JobType.class);
  public static final Set<String> SCHEDULE_TYPES = Set.of("CRON", "FIXED_RATE", "MANUAL");
  public static final Set<String> RETRY_POLICIES = DictEnum.codes(RetryPolicyType.class);
  public static final Set<String> SHARD_STRATEGIES = DictEnum.codes(ShardStrategy.class);
  public static final Set<String> EXECUTION_MODES = DictEnum.codes(ExecutionMode.class);
  public static final Set<String> CHANNEL_TYPES = DictEnum.codes(FileChannelType.class);
  public static final Set<String> AUTH_TYPES = DictEnum.codes(FileChannelAuthType.class);
  public static final Set<String> RECEIPT_POLICIES = DictEnum.codes(FileReceiptPolicy.class);
  public static final Set<String> PIPELINE_TYPES = DictEnum.codes(PipelineType.class);
  // 旧的 STAGE_CODES 是跨 module 的 union，现在只保留做"基础形状校验"；精确校验按
  // pipeline_type 查 STAGES_BY_TYPE（对齐 worker 侧 ImportStage / ExportStage / DispatchStage
  // 三个 enum 的实际值，避免 Excel 里填 PREPROCESS 到 EXPORT 管线这种 cross-module 错配）。
  public static final Set<String> STAGE_CODES = Set.of(
      "RECEIVE",
      "PREPROCESS",
      "PARSE",
      "VALIDATE",
      "LOAD",
      "FEEDBACK",
      "PREPARE",
      "COMPUTE",
      "GENERATE",
      "STORE",
      "REGISTER",
      "COMPLETE",
      "COMMIT",
      "DISPATCH",
      "ACK",
      "RETRY",
      "COMPENSATE");
  public static final Map<String, Set<String>> STAGES_BY_TYPE = Map.of(
      "IMPORT", Set.of("RECEIVE", "PREPROCESS", "PARSE", "VALIDATE", "LOAD", "FEEDBACK"),
      "EXPORT", Set.of("PREPARE", "GENERATE", "STORE", "REGISTER", "COMPLETE"),
      "PROCESS", Set.of("PREPARE", "COMPUTE", "VALIDATE", "COMMIT", "FEEDBACK"),
      "DISPATCH", Set.of("PREPARE", "DISPATCH", "ACK", "RETRY", "COMPENSATE", "COMPLETE"));
  public static final Set<String> WORKFLOW_TYPES = DictEnum.codes(WorkflowType.class);
  public static final Set<String> NODE_TYPES = DictEnum.codes(WorkflowNodeType.class);
  public static final Set<String> EDGE_TYPES = DictEnum.codes(WorkflowEdgeType.class);

  private final JobDefinitionMapper jobDefinitionMapper;
  private final PipelineDefinitionMapper pipelineDefinitionMapper;
  private final StepRegistryQueryMapper stepRegistryQueryMapper;
  private final FileTemplateConfigMapper fileTemplateConfigMapper;
  private final ResourceQueueMapper resourceQueueMapper;
  private final BusinessCalendarMapper businessCalendarMapper;
  private final BatchWindowMapper batchWindowMapper;
  private final ConfigPackageExcelRowValidators rowValidators;

  public ConfigPackageExcelValidator(
      JobDefinitionMapper jobDefinitionMapper,
      PipelineDefinitionMapper pipelineDefinitionMapper,
      StepRegistryQueryMapper stepRegistryQueryMapper,
      FileTemplateConfigMapper fileTemplateConfigMapper,
      ResourceQueueMapper resourceQueueMapper,
      BusinessCalendarMapper businessCalendarMapper,
      BatchWindowMapper batchWindowMapper) {
    this.jobDefinitionMapper = jobDefinitionMapper;
    this.pipelineDefinitionMapper = pipelineDefinitionMapper;
    this.stepRegistryQueryMapper = stepRegistryQueryMapper;
    this.fileTemplateConfigMapper = fileTemplateConfigMapper;
    this.resourceQueueMapper = resourceQueueMapper;
    this.businessCalendarMapper = businessCalendarMapper;
    this.batchWindowMapper = batchWindowMapper;
    this.rowValidators =
        new ConfigPackageExcelRowValidators(stepRegistryQueryMapper, fileTemplateConfigMapper);
  }

  public record SheetResult(
      String sheetName,
      int total,
      List<Map<String, String>> validRows,
      List<WorkbookIssue> issues) {
    public int valid() {
      return validRows.size();
    }

    public int invalid() {
      return total - validRows.size();
    }
  }

  public record PackageValidationResult(
      SheetResult resourceQueues,
      SheetResult businessCalendars,
      SheetResult batchWindows,
      SheetResult jobs,
      SheetResult channels,
      SheetResult fileTemplates,
      SheetResult pipelines,
      SheetResult steps,
      SheetResult wfDefs,
      SheetResult wfNodes,
      SheetResult wfEdges,
      List<WorkbookIssue> crossRefIssues) {

    public int totalInvalid() {
      return resourceQueues.invalid()
          + businessCalendars.invalid()
          + batchWindows.invalid()
          + jobs.invalid()
          + channels.invalid()
          + fileTemplates.invalid()
          + pipelines.invalid()
          + steps.invalid()
          + wfDefs.invalid()
          + wfNodes.invalid()
          + wfEdges.invalid()
          + crossRefIssues.size();
    }

    public List<Map<String, String>> validJobs() {
      return jobs.validRows();
    }

    public List<Map<String, String>> validResourceQueues() {
      return resourceQueues.validRows();
    }

    public List<Map<String, String>> validBusinessCalendars() {
      return businessCalendars.validRows();
    }

    public List<Map<String, String>> validBatchWindows() {
      return batchWindows.validRows();
    }

    public List<Map<String, String>> validChannels() {
      return channels.validRows();
    }

    public List<Map<String, String>> validFileTemplates() {
      return fileTemplates.validRows();
    }

    public List<Map<String, String>> validPipelines() {
      return pipelines.validRows();
    }

    public List<Map<String, String>> validSteps() {
      return steps.validRows();
    }

    public List<Map<String, String>> validWfDefs() {
      return wfDefs.validRows();
    }

    public List<Map<String, String>> validWfNodes() {
      return wfNodes.validRows();
    }

    public List<Map<String, String>> validWfEdges() {
      return wfEdges.validRows();
    }

    public List<WorkbookIssue> allIssues() {
      List<WorkbookIssue> all = new ArrayList<>();
      all.addAll(resourceQueues.issues());
      all.addAll(businessCalendars.issues());
      all.addAll(batchWindows.issues());
      all.addAll(jobs.issues());
      all.addAll(channels.issues());
      all.addAll(fileTemplates.issues());
      all.addAll(pipelines.issues());
      all.addAll(steps.issues());
      all.addAll(wfDefs.issues());
      all.addAll(wfNodes.issues());
      all.addAll(wfEdges.issues());
      all.addAll(crossRefIssues);
      return all;
    }
  }

  @FunctionalInterface
  private interface ExcelRowValidator {
    void validate(Map<String, String> row, int rowNo, List<String> rowIssues);
  }

  private static SheetResult validateRows(
      String sheetName, List<Map<String, String>> rows, ExcelRowValidator validator) {
    List<WorkbookIssue> issues = new ArrayList<>();
    List<Map<String, String>> valid = new ArrayList<>();
    int rowNo = 2;
    for (Map<String, String> row : rows) {
      List<String> rowIssues = new ArrayList<>();
      validator.validate(row, rowNo, rowIssues);
      addIssues(rowIssues, sheetName, rowNo, issues);
      if (rowIssues.isEmpty()) {
        valid.add(withRowNo(row, rowNo));
      }
      rowNo++;
    }
    return new SheetResult(sheetName, rows.size(), valid, issues);
  }

  public PackageValidationResult validate(PackageExcelSession session) {
    String tid = session.tenantId();
    SheetResult resourceQueues = validateResourceQueueRows(tid, session.resourceQueueRows());
    SheetResult businessCalendars =
        validateBusinessCalendarRows(tid, session.businessCalendarRows());
    SheetResult batchWindows = validateBatchWindowRows(tid, session.batchWindowRows());
    SheetResult jobs = validateJobRows(tid, session.jobRows());
    SheetResult channels = validateChannelRows(tid, session.fileChannelRows());
    SheetResult fileTemplates = validateFileTemplateRows(tid, session.fileTemplateRows());
    SheetResult pipelines = validatePipelineRows(tid, session.pipelineRows());
    SheetResult steps = validateStepRows(session.pipelineStepRows(), pipelines.validRows());
    SheetResult wfDefs = validateWfDefRows(tid, session.workflowDefinitionRows());
    SheetResult wfNodes = validateWfNodeRows(tid, session.workflowNodeRows(), wfDefs.validRows());
    SheetResult wfEdges = validateWfEdgeRows(
        tid, session.workflowEdgeRows(), wfDefs.validRows(), wfNodes.validRows());
    List<WorkbookIssue> crossIssues = validateCrossReferences(
        tid,
        resourceQueues.validRows(),
        businessCalendars.validRows(),
        batchWindows.validRows(),
        jobs.validRows(),
        fileTemplates.validRows(),
        pipelines.validRows(),
        steps.validRows(),
        wfNodes.validRows(),
        session.pipelineRows());
    // ADR-025:Excel import 阶段静态 DAG 拓扑校验,拒绝有环/不可达/孤立终端/CONDITION 缺 expr/
    // DSL 引用非法或非上游节点的图。复杂规则(V9/V10 GATEWAY join_mode 与 V16 WAIT sensor_spec)留 enable 时由
    // orchestrator WorkflowGraphValidator 回退,Excel 阶段先拦截致命问题。
    crossIssues.addAll(
        WorkflowGraphTopologyValidator.validate(wfNodes.validRows(), wfEdges.validRows()));
    return new PackageValidationResult(
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
        wfEdges,
        crossIssues);
  }

  private SheetResult validateResourceQueueRows(String tenantId, List<Map<String, String>> rows) {
    Set<String> seen = new LinkedHashSet<>();
    return validateRows(RESOURCE_QUEUE_SHEET, rows, (row, rowNo, ri) -> {
      QueueRow queue = ResourceQueueExcelRowParser.parseRow(tenantId, rowNo, row, ri);
      if (hasText(queue.queueCode()) && !seen.add(queue.queueCode())) {
        ri.add("duplicate queue_code in excel: " + queue.queueCode());
      }
    });
  }

  private SheetResult validateBusinessCalendarRows(
      String tenantId, List<Map<String, String>> rows) {
    Set<String> seen = new LinkedHashSet<>();
    return validateRows(BUSINESS_CALENDAR_SHEET, rows, (row, rowNo, ri) -> {
      CalendarRow calendar = BusinessCalendarExcelRowParser.parseRow(tenantId, rowNo, row, ri);
      if (hasText(calendar.calendarCode()) && !seen.add(calendar.calendarCode())) {
        ri.add("duplicate calendar_code in excel: " + calendar.calendarCode());
      }
    });
  }

  private SheetResult validateBatchWindowRows(String tenantId, List<Map<String, String>> rows) {
    Set<String> seen = new LinkedHashSet<>();
    return validateRows(BATCH_WINDOW_SHEET, rows, (row, rowNo, ri) -> {
      WindowRow window = BatchWindowExcelRowParser.parseRow(tenantId, rowNo, row, ri);
      if (hasText(window.windowCode()) && !seen.add(window.windowCode())) {
        ri.add("duplicate window_code in excel: " + window.windowCode());
      }
    });
  }

  private SheetResult validateJobRows(String tenantId, List<Map<String, String>> rows) {
    Set<String> seen = new LinkedHashSet<>();
    return validateRows(
        JOB_SHEET,
        rows,
        (row, rowNo, ri) ->
            ConfigPackageExcelRowValidators.validateJobRow(tenantId, row, seen, ri));
  }

  private SheetResult validateChannelRows(String tenantId, List<Map<String, String>> rows) {
    Set<String> seen = new LinkedHashSet<>();
    return validateRows(
        CHANNEL_SHEET,
        rows,
        (row, rowNo, ri) ->
            ConfigPackageExcelRowValidators.validateChannelRow(tenantId, row, seen, ri));
  }

  private SheetResult validateFileTemplateRows(String tenantId, List<Map<String, String>> rows) {
    Set<String> seen = new LinkedHashSet<>();
    return validateRows(FILE_TEMPLATE_SHEET, rows, (row, rowNo, ri) -> {
      TemplateRow template = FileTemplateExcelRowParser.parseRow(tenantId, rowNo, row, ri);
      ConfigPackageExcelRowValidators.validateFormatConditionals(row, ri);
      ConfigPackageExcelRowValidators.validateExportSql(row, ri);
      rowValidators.validateTemplateJsonStructure(row, ri);
      String key =
          ConfigPackageExcelRowValidators.templateKey(template.templateCode(), template.version());
      if (hasText(template.templateCode()) && !seen.add(key)) {
        ri.add("duplicate template_code + version in excel: " + key);
      }
    });
  }

  private SheetResult validatePipelineRows(String tenantId, List<Map<String, String>> rows) {
    Set<String> seen = new LinkedHashSet<>();
    return validateRows(
        PIPELINE_SHEET,
        rows,
        (row, rowNo, ri) ->
            ConfigPackageExcelRowValidators.validatePipelineRow(tenantId, row, seen, ri));
  }

  private SheetResult validateStepRows(
      List<Map<String, String>> rows, List<Map<String, String>> validPipelineRows) {
    Set<String> pipelineKeys = validPipelineRows.stream()
        .map(r -> normalize(r.get(COL_JOB_CODE)) + KEY_SEP_COLON + normalize(r.get(COL_VERSION)))
        .collect(Collectors.toSet());
    Map<String, String> pipelineKeyToType =
        ConfigPackageExcelRowValidators.buildPipelineKeyToType(validPipelineRows);
    // 按模块懒加载 step_registry 白名单；空集表示该 module 的 worker 未启动过登记，降级为不校验
    // （防止首次部署没跑 worker 就导致所有上传被拒）
    Map<String, Set<String>> registryByModule = new HashMap<>();
    Set<String> seen = new LinkedHashSet<>();
    // 业务表/列精确校验的"硬拦截"不放在 Validator：这里仅校验 Excel 格式、枚举与 registry。
    return validateRows(
        STEP_SHEET,
        rows,
        (row, rowNo, ri) -> rowValidators.validateStepRow(
            row, pipelineKeys, pipelineKeyToType, registryByModule, seen, ri));
  }

  private SheetResult validateWfDefRows(String tenantId, List<Map<String, String>> rows) {
    Set<String> seen = new LinkedHashSet<>();
    return validateRows(
        WF_DEF_SHEET,
        rows,
        (row, rowNo, ri) ->
            ConfigPackageExcelRowValidators.validateWfDefRow(tenantId, row, seen, ri));
  }

  private SheetResult validateWfNodeRows(
      String tenantId, List<Map<String, String>> rows, List<Map<String, String>> validWfDefs) {
    Set<String> wfKeys = validWfDefs.stream()
        .map(r ->
            normalize(r.get(COL_WORKFLOW_CODE)) + KEY_SEP_COLON + normalize(r.get(COL_VERSION)))
        .collect(Collectors.toSet());
    Set<String> seen = new LinkedHashSet<>();
    return validateRows(
        WF_NODE_SHEET,
        rows,
        (row, rowNo, ri) ->
            ConfigPackageExcelRowValidators.validateWfNodeRow(row, wfKeys, seen, ri));
  }

  private SheetResult validateWfEdgeRows(
      String tenantId,
      List<Map<String, String>> rows,
      List<Map<String, String>> validWfDefs,
      List<Map<String, String>> validNodes) {
    Set<String> wfKeys = validWfDefs.stream()
        .map(r ->
            normalize(r.get(COL_WORKFLOW_CODE)) + KEY_SEP_COLON + normalize(r.get(COL_VERSION)))
        .collect(Collectors.toSet());
    Set<String> nodeKeys = validNodes.stream()
        .map(r -> normalize(r.get(COL_WORKFLOW_CODE))
            + KEY_SEP_COLON
            + normalize(r.get(COL_WORKFLOW_VERSION))
            + KEY_SEP_HASH
            + normalize(r.get(COL_NODE_CODE)))
        .collect(Collectors.toSet());
    return validateRows(
        WF_EDGE_SHEET,
        rows,
        (row, rowNo, ri) ->
            ConfigPackageExcelRowValidators.validateWfEdgeRow(row, wfKeys, nodeKeys, ri));
  }

  // Excel 跨表 cross-reference 校验需要并列接收所有 sheet 的合法行集合 + ctx,
  // 拆 Param 对象会让调用点失去类型安全（Excel 行是 Map<String,String>）；抑制 PMD。
  @SuppressWarnings("PMD.ExcessiveParameterList")
  private List<WorkbookIssue> validateCrossReferences(
      String tenantId,
      List<Map<String, String>> validResourceQueues,
      List<Map<String, String>> validBusinessCalendars,
      List<Map<String, String>> validBatchWindows,
      List<Map<String, String>> validJobs,
      List<Map<String, String>> validFileTemplates,
      List<Map<String, String>> validPipelines,
      List<Map<String, String>> validSteps,
      List<Map<String, String>> validWfNodes,
      List<Map<String, String>> allPipelineRows) {
    Set<String> jobCodesInExcel = validJobs.stream()
        .map(r -> normalize(r.get(COL_JOB_CODE)))
        .filter(Texts::hasText)
        .collect(Collectors.toSet());
    Set<String> pipelineJobCodesInExcel = validPipelines.stream()
        .map(r -> normalize(r.get(COL_JOB_CODE)))
        .filter(Texts::hasText)
        .collect(Collectors.toSet());
    Set<String> fileTemplatesInExcel =
        ConfigPackageExcelRowValidators.buildFileTemplateKeys(validFileTemplates);
    Set<String> queueCodesInExcel = extractCodes(validResourceQueues, COL_QUEUE_CODE);
    Set<String> calendarCodesInExcel = extractCodes(validBusinessCalendars, COL_CALENDAR_CODE);
    Set<String> windowCodesInExcel = extractCodes(validBatchWindows, COL_WINDOW_CODE);
    List<WorkbookIssue> issues = new ArrayList<>();

    addJobDependencyIssues(
        tenantId,
        validJobs,
        jobCodesInExcel,
        queueCodesInExcel,
        calendarCodesInExcel,
        windowCodesInExcel,
        issues);

    int rowNo = 2;
    for (Map<String, String> row : allPipelineRows) {
      String jobCode = normalize(row.get(COL_JOB_CODE));
      if (hasText(jobCode)
          && !jobCodesInExcel.contains(jobCode)
          && jobDefinitionMapper.selectByUniqueKey(tenantId, jobCode) == null) {
        issues.add(new WorkbookIssue(
            PIPELINE_SHEET,
            rowNo,
            COL_JOB_CODE,
            "job_code references unknown job definition: " + jobCode));
      }
      rowNo++;
    }

    addTemplateReferenceIssues(
        tenantId, JOB_SHEET, COL_DEFAULT_PARAMS, validJobs, fileTemplatesInExcel, issues);
    addTemplateReferenceIssues(
        tenantId, STEP_SHEET, "step_params", validSteps, fileTemplatesInExcel, issues);

    int fallbackRowNo = 2;
    for (Map<String, String> row : validWfNodes) {
      int wfNodeRowNo = excelRowNo(row, fallbackRowNo);
      String relatedJob = normalize(row.get(COL_RELATED_JOB_CODE));
      if (hasText(relatedJob)
          && !jobCodesInExcel.contains(relatedJob)
          && jobDefinitionMapper.selectByUniqueKey(tenantId, relatedJob) == null) {
        issues.add(new WorkbookIssue(
            WF_NODE_SHEET,
            wfNodeRowNo,
            COL_RELATED_JOB_CODE,
            "related_job_code references unknown job definition: " + relatedJob));
      }
      String relatedPipeline = normalize(row.get(COL_RELATED_PIPELINE_CODE));
      if (hasText(relatedPipeline) && !pipelineJobCodesInExcel.contains(relatedPipeline)) {
        List<Map<String, Object>> found = pipelineDefinitionMapper.selectByQuery(
            tenantId, relatedPipeline, null, null, new PageRequest(1, 1));
        if (found == null || found.isEmpty()) {
          issues.add(new WorkbookIssue(
              WF_NODE_SHEET,
              wfNodeRowNo,
              COL_RELATED_PIPELINE_CODE,
              "related_pipeline_code references unknown pipeline: " + relatedPipeline));
        }
      }
      String windowCode = normalize(row.get(COL_WINDOW_CODE));
      if (hasText(windowCode)
          && !windowCodesInExcel.contains(windowCode)
          && !batchWindowExists(tenantId, windowCode)) {
        issues.add(new WorkbookIssue(
            WF_NODE_SHEET,
            wfNodeRowNo,
            COL_WINDOW_CODE,
            "window_code references unknown batch_window: " + windowCode));
      }
      fallbackRowNo++;
    }
    return issues;
  }

  private void addJobDependencyIssues(
      String tenantId,
      List<Map<String, String>> rows,
      Set<String> jobCodesInExcel,
      Set<String> queueCodesInExcel,
      Set<String> calendarCodesInExcel,
      Set<String> windowCodesInExcel,
      List<WorkbookIssue> issues) {
    int fallbackRowNo = 2;
    for (Map<String, String> row : rows) {
      int rowNo = excelRowNo(row, fallbackRowNo);
      String queueCode = normalize(row.get(COL_QUEUE_CODE));
      if (hasText(queueCode)
          && !queueCodesInExcel.contains(queueCode)
          && !resourceQueueExists(tenantId, queueCode)) {
        issues.add(new WorkbookIssue(
            JOB_SHEET,
            rowNo,
            COL_QUEUE_CODE,
            "queue_code references unknown resource_queue: " + queueCode));
      }
      String dependsOnJobCode = normalize(row.get(COL_DEPENDS_ON_JOB_CODE));
      if (hasText(dependsOnJobCode)
          && !jobCodesInExcel.contains(dependsOnJobCode)
          && jobDefinitionMapper.selectByUniqueKey(tenantId, dependsOnJobCode) == null) {
        issues.add(new WorkbookIssue(
            JOB_SHEET,
            rowNo,
            COL_DEPENDS_ON_JOB_CODE,
            "depends_on_job_code references unknown job definition: " + dependsOnJobCode));
      }
      String calendarCode = normalize(row.get(COL_CALENDAR_CODE));
      if (hasText(calendarCode)
          && !calendarCodesInExcel.contains(calendarCode)
          && !businessCalendarExists(tenantId, calendarCode)) {
        issues.add(new WorkbookIssue(
            JOB_SHEET,
            rowNo,
            COL_CALENDAR_CODE,
            "calendar_code references unknown business_calendar: " + calendarCode));
      }
      String windowCode = normalize(row.get(COL_WINDOW_CODE));
      if (hasText(windowCode)
          && !windowCodesInExcel.contains(windowCode)
          && !batchWindowExists(tenantId, windowCode)) {
        issues.add(new WorkbookIssue(
            JOB_SHEET,
            rowNo,
            COL_WINDOW_CODE,
            "window_code references unknown batch_window: " + windowCode));
      }
      fallbackRowNo++;
    }
  }

  private boolean resourceQueueExists(String tenantId, String queueCode) {
    Map<String, Object> found = resourceQueueMapper.selectByUniqueKey(tenantId, queueCode);
    return found != null && !found.isEmpty();
  }

  private boolean businessCalendarExists(String tenantId, String calendarCode) {
    Map<String, Object> found =
        businessCalendarMapper.selectActiveByTenantAndCalendarCode(tenantId, calendarCode);
    return found != null && !found.isEmpty();
  }

  private boolean batchWindowExists(String tenantId, String windowCode) {
    Map<String, Object> found = batchWindowMapper.selectByUniqueKey(tenantId, windowCode);
    return found != null && !found.isEmpty();
  }

  private static Set<String> extractCodes(List<Map<String, String>> rows, String column) {
    return rows.stream()
        .map(r -> normalize(r.get(column)))
        .filter(Texts::hasText)
        .collect(Collectors.toSet());
  }

  private static Map<String, String> withRowNo(Map<String, String> row, int rowNo) {
    Map<String, String> copy = new LinkedHashMap<>(row);
    copy.put(INTERNAL_ROW_NO, String.valueOf(rowNo));
    return copy;
  }

  static int excelRowNo(Map<String, String> row, int fallbackRowNo) {
    String raw = row.get(INTERNAL_ROW_NO);
    if (!Texts.hasText(raw)) {
      return fallbackRowNo;
    }
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException ignored) {
      return fallbackRowNo;
    }
  }

  private void addTemplateReferenceIssues(
      String tenantId,
      String sheetName,
      String jsonColumn,
      List<Map<String, String>> rows,
      Set<String> fileTemplatesInExcel,
      List<WorkbookIssue> issues) {
    int fallbackRowNo = 2;
    for (Map<String, String> row : rows) {
      int rowNo = excelRowNo(row, fallbackRowNo);
      ConfigPackageExcelRowValidators.TemplateRef ref =
          rowValidators.extractTemplateRef(row.get(jsonColumn));
      if (ref.hasTemplateCode()
          && !fileTemplatesInExcel.contains(
              ConfigPackageExcelRowValidators.templateKey(ref.templateCode(), ref.version()))
          && !rowValidators.fileTemplateExists(tenantId, ref)) {
        issues.add(new WorkbookIssue(
            sheetName,
            rowNo,
            jsonColumn,
            "templateCode references unknown file_template_config: "
                + ConfigPackageExcelRowValidators.templateKey(ref.templateCode(), ref.version())));
      }
      fallbackRowNo++;
    }
  }

  public static String normalize(String value) {
    return ConsoleTextSanitizer.normalize(value);
  }

  public static String normalizeEnum(String value) {
    String n = normalize(value);
    return n == null ? null : n.toUpperCase(Locale.ROOT);
  }

  public static boolean hasText(String value) {
    return Texts.hasText(value);
  }

  private static void addIssues(
      List<String> rowIssues, String sheetName, int rowNo, List<WorkbookIssue> issues) {
    for (String msg : rowIssues) {
      issues.add(new WorkbookIssue(sheetName, rowNo, null, msg));
    }
  }
}
