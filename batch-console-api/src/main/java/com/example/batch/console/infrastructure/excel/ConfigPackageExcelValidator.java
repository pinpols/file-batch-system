package com.example.batch.console.infrastructure.excel;

import static com.example.batch.console.support.excel.SheetValidationHelpers.optionalEnum;
import static com.example.batch.console.support.excel.SheetValidationHelpers.requireField;
import static com.example.batch.console.support.excel.SheetValidationHelpers.requireIntField;
import static com.example.batch.console.support.excel.SheetValidationHelpers.requiredEnum;
import static com.example.batch.console.support.excel.SheetValidationHelpers.validateJsonField;

import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.FileChannelAuthType;
import com.example.batch.common.enums.FileChannelType;
import com.example.batch.common.enums.FileReceiptPolicy;
import com.example.batch.common.enums.JobType;
import com.example.batch.common.enums.PipelineType;
import com.example.batch.common.enums.RetryPolicyType;
import com.example.batch.common.enums.ShardStrategy;
import com.example.batch.common.enums.WorkflowEdgeType;
import com.example.batch.common.enums.WorkflowNodeType;
import com.example.batch.common.enums.WorkflowType;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.persistence.BatchColumnNames;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.Texts;
import com.example.batch.console.domain.workflow.mapper.PipelineDefinitionMapper;
import com.example.batch.console.infrastructure.excel.BatchWindowExcelRowParser.WindowRow;
import com.example.batch.console.infrastructure.excel.BusinessCalendarExcelRowParser.CalendarRow;
import com.example.batch.console.infrastructure.excel.FileTemplateExcelRowParser.TemplateRow;
import com.example.batch.console.infrastructure.excel.ResourceQueueExcelRowParser.QueueRow;
import com.example.batch.console.mapper.BatchWindowMapper;
import com.example.batch.console.mapper.BusinessCalendarMapper;
import com.example.batch.console.mapper.FileTemplateConfigMapper;
import com.example.batch.console.mapper.JobDefinitionMapper;
import com.example.batch.console.mapper.ResourceQueueMapper;
import com.example.batch.console.mapper.StepRegistryQueryMapper;
import com.example.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import com.example.batch.console.support.excel.TenantConfigPackageExcelImportStore.PackageExcelSession;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates rows parsed from the tenant config package Excel workbook. Extracted from
 * DefaultConsoleTenantConfigPackageExcelApplicationService to reduce class size.
 */
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
  public static final String COL_JOB_CODE = "job_code";
  public static final String COL_JOB_NAME = "job_name";
  public static final String COL_JOB_TYPE = "job_type";
  public static final String COL_SCHEDULE_TYPE = "schedule_type";
  public static final String COL_SCHEDULE_EXPR = "schedule_expr";
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
  public static final Set<String> CHANNEL_TYPES = DictEnum.codes(FileChannelType.class);
  public static final Set<String> AUTH_TYPES = DictEnum.codes(FileChannelAuthType.class);
  public static final Set<String> RECEIPT_POLICIES = DictEnum.codes(FileReceiptPolicy.class);
  public static final Set<String> PIPELINE_TYPES = DictEnum.codes(PipelineType.class);
  // 旧的 STAGE_CODES 是跨 module 的 union，现在只保留做"基础形状校验"；精确校验按
  // pipeline_type 查 STAGES_BY_TYPE（对齐 worker 侧 ImportStage / ExportStage / DispatchStage
  // 三个 enum 的实际值，避免 Excel 里填 PREPROCESS 到 EXPORT 管线这种 cross-module 错配）。
  public static final Set<String> STAGE_CODES =
      Set.of(
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
  public static final Map<String, Set<String>> STAGES_BY_TYPE =
      Map.of(
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
  private final ObjectMapper objectMapper = new ObjectMapper();

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
    SheetResult wfEdges =
        validateWfEdgeRows(
            tid, session.workflowEdgeRows(), wfDefs.validRows(), wfNodes.validRows());
    List<WorkbookIssue> crossIssues =
        validateCrossReferences(
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
    // orchestrator WorkflowGraphValidator 兜底,Excel 阶段先拦截致命问题。
    crossIssues.addAll(validateWorkflowGraphTopology(wfNodes.validRows(), wfEdges.validRows()));
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
    List<WorkbookIssue> issues = new ArrayList<>();
    List<Map<String, String>> valid = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int rowNo = 2;
    for (Map<String, String> row : rows) {
      List<String> ri = new ArrayList<>();
      QueueRow queue = ResourceQueueExcelRowParser.parseRow(tenantId, rowNo, row, ri);
      if (hasText(queue.queueCode()) && !seen.add(queue.queueCode())) {
        ri.add("duplicate queue_code in excel: " + queue.queueCode());
      }
      addIssues(ri, RESOURCE_QUEUE_SHEET, rowNo, issues);
      if (ri.isEmpty()) {
        valid.add(withRowNo(row, rowNo));
      }
      rowNo++;
    }
    return new SheetResult(RESOURCE_QUEUE_SHEET, rows.size(), valid, issues);
  }

  private SheetResult validateBusinessCalendarRows(
      String tenantId, List<Map<String, String>> rows) {
    List<WorkbookIssue> issues = new ArrayList<>();
    List<Map<String, String>> valid = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int rowNo = 2;
    for (Map<String, String> row : rows) {
      List<String> ri = new ArrayList<>();
      CalendarRow calendar = BusinessCalendarExcelRowParser.parseRow(tenantId, rowNo, row, ri);
      if (hasText(calendar.calendarCode()) && !seen.add(calendar.calendarCode())) {
        ri.add("duplicate calendar_code in excel: " + calendar.calendarCode());
      }
      addIssues(ri, BUSINESS_CALENDAR_SHEET, rowNo, issues);
      if (ri.isEmpty()) {
        valid.add(withRowNo(row, rowNo));
      }
      rowNo++;
    }
    return new SheetResult(BUSINESS_CALENDAR_SHEET, rows.size(), valid, issues);
  }

  private SheetResult validateBatchWindowRows(String tenantId, List<Map<String, String>> rows) {
    List<WorkbookIssue> issues = new ArrayList<>();
    List<Map<String, String>> valid = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int rowNo = 2;
    for (Map<String, String> row : rows) {
      List<String> ri = new ArrayList<>();
      WindowRow window = BatchWindowExcelRowParser.parseRow(tenantId, rowNo, row, ri);
      if (hasText(window.windowCode()) && !seen.add(window.windowCode())) {
        ri.add("duplicate window_code in excel: " + window.windowCode());
      }
      addIssues(ri, BATCH_WINDOW_SHEET, rowNo, issues);
      if (ri.isEmpty()) {
        valid.add(withRowNo(row, rowNo));
      }
      rowNo++;
    }
    return new SheetResult(BATCH_WINDOW_SHEET, rows.size(), valid, issues);
  }

  private SheetResult validateJobRows(String tenantId, List<Map<String, String>> rows) {
    List<WorkbookIssue> issues = new ArrayList<>();
    List<Map<String, String>> valid = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int rowNo = 2;
    for (Map<String, String> row : rows) {
      List<String> ri = new ArrayList<>();
      validateJobRow(tenantId, row, seen, ri);
      addIssues(ri, JOB_SHEET, rowNo, issues);
      if (ri.isEmpty()) {
        valid.add(withRowNo(row, rowNo));
      }
      rowNo++;
    }
    return new SheetResult(JOB_SHEET, rows.size(), valid, issues);
  }

  private static void validateJobRow(
      String tenantId, Map<String, String> row, Set<String> seen, List<String> ri) {
    String jobCode = normalize(row.get(COL_JOB_CODE));
    requireField(ri, jobCode, "job_code");
    requireField(ri, normalize(row.get(COL_JOB_NAME)), "job_name");
    requiredEnum(normalizeEnum(row.get(COL_JOB_TYPE)), "job_type", JOB_TYPES, ri);
    requiredEnum(normalizeEnum(row.get(COL_SCHEDULE_TYPE)), "schedule_type", SCHEDULE_TYPES, ri);
    optionalEnum(normalizeEnum(row.get(COL_RETRY_POLICY)), "retry_policy", RETRY_POLICIES, ri);
    optionalEnum(
        normalizeEnum(row.get(COL_SHARD_STRATEGY)), "shard_strategy", SHARD_STRATEGIES, ri);
    validateJsonField(row.get(COL_PARAM_SCHEMA), "param_schema", false, ri);
    if (hasText(jobCode) && !seen.add(tenantId + KEY_SEP_HASH + jobCode)) {
      ri.add("duplicate job_code in excel: " + jobCode);
    }
  }

  private SheetResult validateChannelRows(String tenantId, List<Map<String, String>> rows) {
    List<WorkbookIssue> issues = new ArrayList<>();
    List<Map<String, String>> valid = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int rowNo = 2;
    for (Map<String, String> row : rows) {
      List<String> ri = new ArrayList<>();
      validateChannelRow(tenantId, row, seen, ri);
      addIssues(ri, CHANNEL_SHEET, rowNo, issues);
      if (ri.isEmpty()) {
        valid.add(withRowNo(row, rowNo));
      }
      rowNo++;
    }
    return new SheetResult(CHANNEL_SHEET, rows.size(), valid, issues);
  }

  private static void validateChannelRow(
      String tenantId, Map<String, String> row, Set<String> seen, List<String> ri) {
    String code = normalize(row.get(COL_CHANNEL_CODE));
    requireField(ri, code, "channel_code");
    requireField(ri, normalize(row.get(COL_CHANNEL_NAME)), "channel_name");
    requiredEnum(normalizeEnum(row.get(COL_CHANNEL_TYPE)), "channel_type", CHANNEL_TYPES, ri);
    requiredEnum(normalizeEnum(row.get(COL_AUTH_TYPE)), "auth_type", AUTH_TYPES, ri);
    requiredEnum(
        normalizeEnum(row.get(COL_RECEIPT_POLICY)), "receipt_policy", RECEIPT_POLICIES, ri);
    validateJsonField(row.get(COL_CONFIG_JSON), "config_json", true, ri);
    if (hasText(code) && !seen.add(tenantId + KEY_SEP_HASH + code)) {
      ri.add("duplicate channel_code in excel: " + code);
    }
  }

  private SheetResult validateFileTemplateRows(String tenantId, List<Map<String, String>> rows) {
    List<WorkbookIssue> issues = new ArrayList<>();
    List<Map<String, String>> valid = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int rowNo = 2;
    for (Map<String, String> row : rows) {
      List<String> ri = new ArrayList<>();
      TemplateRow template = FileTemplateExcelRowParser.parseRow(tenantId, rowNo, row, ri);
      String key = templateKey(template.templateCode(), template.version());
      if (hasText(template.templateCode()) && !seen.add(key)) {
        ri.add("duplicate template_code + version in excel: " + key);
      }
      addIssues(ri, FILE_TEMPLATE_SHEET, rowNo, issues);
      if (ri.isEmpty()) {
        valid.add(withRowNo(row, rowNo));
      }
      rowNo++;
    }
    return new SheetResult(FILE_TEMPLATE_SHEET, rows.size(), valid, issues);
  }

  private SheetResult validatePipelineRows(String tenantId, List<Map<String, String>> rows) {
    List<WorkbookIssue> issues = new ArrayList<>();
    List<Map<String, String>> valid = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int rowNo = 2;
    for (Map<String, String> row : rows) {
      List<String> ri = new ArrayList<>();
      validatePipelineRow(tenantId, row, seen, ri);
      addIssues(ri, PIPELINE_SHEET, rowNo, issues);
      if (ri.isEmpty()) {
        valid.add(withRowNo(row, rowNo));
      }
      rowNo++;
    }
    return new SheetResult(PIPELINE_SHEET, rows.size(), valid, issues);
  }

  private static void validatePipelineRow(
      String tenantId, Map<String, String> row, Set<String> seen, List<String> ri) {
    String jobCode = normalize(row.get(COL_JOB_CODE));
    String version = normalize(row.get(COL_VERSION));
    requireField(ri, jobCode, "job_code");
    requireField(ri, normalize(row.get(COL_PIPELINE_NAME)), "pipeline_name");
    requiredEnum(normalizeEnum(row.get(COL_PIPELINE_TYPE)), "pipeline_type", PIPELINE_TYPES, ri);
    requireIntField(version, "version", ri);
    if (hasText(jobCode)
        && hasText(version)
        && !seen.add(tenantId + KEY_SEP_HASH + jobCode + KEY_SEP_COLON + version)) {
      ri.add("duplicate pipeline key (job_code + version): " + jobCode + KEY_SEP_COLON + version);
    }
  }

  private SheetResult validateStepRows(
      List<Map<String, String>> rows, List<Map<String, String>> validPipelineRows) {
    Set<String> pipelineKeys =
        validPipelineRows.stream()
            .map(
                r -> normalize(r.get(COL_JOB_CODE)) + KEY_SEP_COLON + normalize(r.get(COL_VERSION)))
            .collect(Collectors.toSet());
    Map<String, String> pipelineKeyToType = buildPipelineKeyToType(validPipelineRows);
    // 按模块懒加载 step_registry 白名单；空集表示该 module 的 worker 未启动过登记，降级为不校验
    // （防止首次部署没跑 worker 就导致所有上传被拒）
    Map<String, Set<String>> registryByModule = new HashMap<>();
    List<WorkbookIssue> issues = new ArrayList<>();
    List<Map<String, String>> valid = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int rowNo = 2;
    for (Map<String, String> row : rows) {
      List<String> ri = new ArrayList<>();
      validateStepRow(row, pipelineKeys, pipelineKeyToType, registryByModule, seen, ri);
      // 业务表/列精确校验的"硬拦截"故意不放在这里——Validator 只做 Excel 格式 + 枚举 / registry
      // 层面的校验，不耦合业务 schema。biz_table_schema 的信息通过模板下拉在 ConfigPackageExcelWorkbookWriter
      // 里以下拉选项形式呈现给填表用户；真正的 schema 漂移由 LoadStep 在运行时报业务错。
      addIssues(ri, STEP_SHEET, rowNo, issues);
      if (ri.isEmpty()) {
        valid.add(withRowNo(row, rowNo));
      }
      rowNo++;
    }
    return new SheetResult(STEP_SHEET, rows.size(), valid, issues);
  }

  private static Map<String, String> buildPipelineKeyToType(
      List<Map<String, String>> validPipelineRows) {
    Map<String, String> out = new HashMap<>();
    for (Map<String, String> p : validPipelineRows) {
      String key = normalize(p.get(COL_JOB_CODE)) + KEY_SEP_COLON + normalize(p.get(COL_VERSION));
      String type = normalizeEnum(p.get(COL_PIPELINE_TYPE));
      if (hasText(type)) {
        out.put(key, type);
      }
    }
    return out;
  }

  private void validateStepRow(
      Map<String, String> row,
      Set<String> pipelineKeys,
      Map<String, String> pipelineKeyToType,
      Map<String, Set<String>> registryByModule,
      Set<String> seen,
      List<String> ri) {
    String jobCode = normalize(row.get(COL_JOB_CODE));
    String version = normalize(row.get(COL_VERSION));
    String stepCode = normalize(row.get(COL_STEP_CODE));
    String implCode = normalize(row.get(COL_IMPL_CODE));
    requireField(ri, jobCode, "job_code");
    requireField(ri, version, "version");
    requireField(ri, stepCode, "step_code");
    requireField(ri, normalize(row.get(COL_STEP_NAME)), "step_name");

    String pipelineKey = jobCode + KEY_SEP_COLON + version;
    validateStageCode(row, pipelineKey, pipelineKeyToType, ri);
    validateRetryPolicy(row, ri);
    validatePipelineLink(jobCode, version, stepCode, pipelineKey, pipelineKeys, seen, ri);
    validateImplCode(row, implCode, pipelineKey, pipelineKeyToType, registryByModule, ri);
  }

  private void validateStageCode(
      Map<String, String> row,
      String pipelineKey,
      Map<String, String> pipelineKeyToType,
      List<String> ri) {
    String stageCode = normalizeEnum(row.get(COL_STAGE_CODE));
    if (!hasText(stageCode)) {
      ri.add("stage_code is required");
      return;
    }
    if (!STAGE_CODES.contains(stageCode)) {
      ri.add("stage_code must be one of " + STAGE_CODES);
      return;
    }
    String pipelineType = pipelineKeyToType.get(pipelineKey);
    if (pipelineType == null) {
      return;
    }
    // 按 pipeline_type 做精确校验：例如 EXPORT 管线不能出现 PREPROCESS/LOAD 这种 IMPORT stage
    Set<String> allowed = STAGES_BY_TYPE.get(pipelineType);
    if (allowed != null && !allowed.contains(stageCode)) {
      ri.add(
          "stage_code '" + stageCode + "' 不属于 pipeline_type '" + pipelineType + "'，允许值：" + allowed);
    }
  }

  private static void validateRetryPolicy(Map<String, String> row, List<String> ri) {
    String retryPolicy = normalizeEnum(row.get(COL_RETRY_POLICY));
    if (hasText(retryPolicy) && !RETRY_POLICIES.contains(retryPolicy)) {
      ri.add("retry_policy must be one of " + RETRY_POLICIES);
    }
  }

  private static void validatePipelineLink(
      String jobCode,
      String version,
      String stepCode,
      String pipelineKey,
      Set<String> pipelineKeys,
      Set<String> seen,
      List<String> ri) {
    if (hasText(jobCode) && hasText(version) && !pipelineKeys.contains(pipelineKey)) {
      ri.add("no matching pipeline for job_code + version: " + pipelineKey);
    }
    if (hasText(jobCode)
        && hasText(version)
        && hasText(stepCode)
        && !seen.add(pipelineKey + KEY_SEP_HASH + stepCode)) {
      ri.add("duplicate step_code in pipeline: " + stepCode);
    }
  }

  /**
   * impl_code 白名单 + 模块匹配（从 {@link #validateStepRows} 提取）：
   *
   * <ul>
   *   <li>支持 MODULE:beanName 前缀格式（模板下载时的下拉项格式），前缀必须等于 pipeline_type
   *   <li>剥掉前缀后 beanName 必须在 step_registry[module] 中
   *   <li>registry 为空（worker 从未启动）时降级为不校验，允许老数据导入
   *   <li>规范化：无论是否带前缀，最终回写到 row 里的 impl_code 都是纯 beanName（DB 存 fileReceive，不存 IMPORT:fileReceive）
   * </ul>
   */
  private void validateImplCode(
      Map<String, String> row,
      String implCode,
      String pipelineKey,
      Map<String, String> pipelineKeyToType,
      Map<String, Set<String>> registryByModule,
      List<String> ri) {
    if (!hasText(implCode) || !pipelineKeyToType.containsKey(pipelineKey)) {
      return;
    }
    String pipelineType = pipelineKeyToType.get(pipelineKey);
    String normalizedImpl = implCode;
    int colonIdx = implCode.indexOf(':');
    if (colonIdx > 0 && colonIdx < implCode.length() - 1) {
      String prefix = implCode.substring(0, colonIdx);
      if (PIPELINE_TYPES.contains(prefix)) {
        if (!prefix.equals(pipelineType)) {
          ri.add(
              "impl_code prefix '"
                  + prefix
                  + "' 与 pipeline_type '"
                  + pipelineType
                  + "' 不匹配，请改选同模块的 Step");
        }
        normalizedImpl = implCode.substring(colonIdx + 1).trim();
        row.put(COL_IMPL_CODE, normalizedImpl);
      }
    }
    Set<String> registered =
        registryByModule.computeIfAbsent(
            pipelineType, m -> new HashSet<>(stepRegistryQueryMapper.selectImplCodesByModule(m)));
    if (!registered.isEmpty() && !registered.contains(normalizedImpl)) {
      ri.add(
          "impl_code '"
              + normalizedImpl
              + "' not registered in module "
              + pipelineType
              + "（检查 Spring bean name 是否存在或 worker 是否启动过以刷新 step_registry）");
    }
  }

  private SheetResult validateWfDefRows(String tenantId, List<Map<String, String>> rows) {
    List<WorkbookIssue> issues = new ArrayList<>();
    List<Map<String, String>> valid = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int rowNo = 2;
    for (Map<String, String> row : rows) {
      List<String> ri = new ArrayList<>();
      validateWfDefRow(tenantId, row, seen, ri);
      addIssues(ri, WF_DEF_SHEET, rowNo, issues);
      if (ri.isEmpty()) {
        valid.add(withRowNo(row, rowNo));
      }
      rowNo++;
    }
    return new SheetResult(WF_DEF_SHEET, rows.size(), valid, issues);
  }

  private static void validateWfDefRow(
      String tenantId, Map<String, String> row, Set<String> seen, List<String> ri) {
    String wfCode = normalize(row.get(COL_WORKFLOW_CODE));
    String version = normalize(row.get(COL_VERSION));
    requireField(ri, wfCode, "workflow_code");
    requireField(ri, normalize(row.get(COL_WORKFLOW_NAME)), "workflow_name");
    requiredEnum(normalizeEnum(row.get(COL_WORKFLOW_TYPE)), "workflow_type", WORKFLOW_TYPES, ri);
    requireIntField(version, "version", ri);
    if (hasText(wfCode)
        && hasText(version)
        && !seen.add(tenantId + KEY_SEP_HASH + wfCode + KEY_SEP_COLON + version)) {
      ri.add("duplicate workflow definition: " + wfCode + KEY_SEP_COLON + version);
    }
  }

  private SheetResult validateWfNodeRows(
      String tenantId, List<Map<String, String>> rows, List<Map<String, String>> validWfDefs) {
    Set<String> wfKeys =
        validWfDefs.stream()
            .map(
                r ->
                    normalize(r.get(COL_WORKFLOW_CODE))
                        + KEY_SEP_COLON
                        + normalize(r.get(COL_VERSION)))
            .collect(Collectors.toSet());
    List<WorkbookIssue> issues = new ArrayList<>();
    List<Map<String, String>> valid = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int rowNo = 2;
    for (Map<String, String> row : rows) {
      List<String> ri = new ArrayList<>();
      validateWfNodeRow(row, wfKeys, seen, ri);
      addIssues(ri, WF_NODE_SHEET, rowNo, issues);
      if (ri.isEmpty()) {
        valid.add(withRowNo(row, rowNo));
      }
      rowNo++;
    }
    return new SheetResult(WF_NODE_SHEET, rows.size(), valid, issues);
  }

  private static void validateWfNodeRow(
      Map<String, String> row, Set<String> wfKeys, Set<String> seen, List<String> ri) {
    String wfCode = normalize(row.get(COL_WORKFLOW_CODE));
    String wfVersion = normalize(row.get(COL_WORKFLOW_VERSION));
    String nodeCode = normalize(row.get(COL_NODE_CODE));
    requireField(ri, wfCode, "workflow_code");
    requireField(ri, wfVersion, "workflow_version");
    requireField(ri, nodeCode, "node_code");
    requireField(ri, normalize(row.get(COL_NODE_NAME)), "node_name");
    requiredEnum(normalizeEnum(row.get(COL_NODE_TYPE)), "node_type", NODE_TYPES, ri);
    optionalEnum(normalizeEnum(row.get(COL_RETRY_POLICY)), "retry_policy", RETRY_POLICIES, ri);
    validateJsonField(row.get(COL_NODE_PARAMS), "node_params", false, ri);
    String wfKey = wfCode + KEY_SEP_COLON + wfVersion;
    if (hasText(wfCode) && hasText(wfVersion) && !wfKeys.contains(wfKey)) {
      ri.add("workflow node references missing definition: " + wfKey);
    }
    if (hasText(wfCode)
        && hasText(wfVersion)
        && hasText(nodeCode)
        && !seen.add(wfKey + KEY_SEP_HASH + nodeCode)) {
      ri.add("duplicate node_code in workflow: " + nodeCode);
    }
  }

  private SheetResult validateWfEdgeRows(
      String tenantId,
      List<Map<String, String>> rows,
      List<Map<String, String>> validWfDefs,
      List<Map<String, String>> validNodes) {
    Set<String> wfKeys =
        validWfDefs.stream()
            .map(
                r ->
                    normalize(r.get(COL_WORKFLOW_CODE))
                        + KEY_SEP_COLON
                        + normalize(r.get(COL_VERSION)))
            .collect(Collectors.toSet());
    Set<String> nodeKeys =
        validNodes.stream()
            .map(
                r ->
                    normalize(r.get(COL_WORKFLOW_CODE))
                        + KEY_SEP_COLON
                        + normalize(r.get(COL_WORKFLOW_VERSION))
                        + KEY_SEP_HASH
                        + normalize(r.get(COL_NODE_CODE)))
            .collect(Collectors.toSet());
    List<WorkbookIssue> issues = new ArrayList<>();
    List<Map<String, String>> valid = new ArrayList<>();
    int rowNo = 2;
    for (Map<String, String> row : rows) {
      List<String> ri = new ArrayList<>();
      validateWfEdgeRow(row, wfKeys, nodeKeys, ri);
      addIssues(ri, WF_EDGE_SHEET, rowNo, issues);
      if (ri.isEmpty()) {
        valid.add(withRowNo(row, rowNo));
      }
      rowNo++;
    }
    return new SheetResult(WF_EDGE_SHEET, rows.size(), valid, issues);
  }

  private static void validateWfEdgeRow(
      Map<String, String> row, Set<String> wfKeys, Set<String> nodeKeys, List<String> ri) {
    String wfCode = normalize(row.get(COL_WORKFLOW_CODE));
    String wfVersion = normalize(row.get(COL_WORKFLOW_VERSION));
    String fromNode = normalize(row.get(COL_FROM_NODE_CODE));
    String toNode = normalize(row.get(COL_TO_NODE_CODE));
    requireField(ri, wfCode, "workflow_code");
    requireField(ri, wfVersion, "workflow_version");
    requireField(ri, fromNode, "from_node_code");
    requireField(ri, toNode, "to_node_code");
    requiredEnum(normalizeEnum(row.get(COL_EDGE_TYPE)), "edge_type", EDGE_TYPES, ri);
    String wfKey = wfCode + KEY_SEP_COLON + wfVersion;
    if (hasText(wfCode) && hasText(wfVersion) && !wfKeys.contains(wfKey)) {
      ri.add("workflow edge references missing definition: " + wfKey);
    }
    requireNodeRef(wfCode, wfVersion, fromNode, wfKey, nodeKeys, "from_node_code", ri);
    requireNodeRef(wfCode, wfVersion, toNode, wfKey, nodeKeys, "to_node_code", ri);
  }

  private static void requireNodeRef(
      String wfCode,
      String wfVersion,
      String node,
      String wfKey,
      Set<String> nodeKeys,
      String field,
      List<String> ri) {
    if (hasText(wfCode)
        && hasText(wfVersion)
        && hasText(node)
        && !nodeKeys.contains(wfKey + KEY_SEP_HASH + node)) {
      ri.add(field + " references unknown node: " + node);
    }
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
    Set<String> jobCodesInExcel =
        validJobs.stream()
            .map(r -> normalize(r.get(COL_JOB_CODE)))
            .filter(Texts::hasText)
            .collect(Collectors.toSet());
    Set<String> pipelineJobCodesInExcel =
        validPipelines.stream()
            .map(r -> normalize(r.get(COL_JOB_CODE)))
            .filter(Texts::hasText)
            .collect(Collectors.toSet());
    Set<String> fileTemplatesInExcel = buildFileTemplateKeys(validFileTemplates);
    Set<String> queueCodesInExcel = extractCodes(validResourceQueues, "queue_code");
    Set<String> calendarCodesInExcel = extractCodes(validBusinessCalendars, "calendar_code");
    Set<String> windowCodesInExcel = extractCodes(validBatchWindows, "window_code");
    List<WorkbookIssue> issues = new ArrayList<>();

    addJobDependencyIssues(
        tenantId, validJobs, queueCodesInExcel, calendarCodesInExcel, windowCodesInExcel, issues);

    int rowNo = 2;
    for (Map<String, String> row : allPipelineRows) {
      String jobCode = normalize(row.get(COL_JOB_CODE));
      if (hasText(jobCode)
          && !jobCodesInExcel.contains(jobCode)
          && jobDefinitionMapper.selectByUniqueKey(tenantId, jobCode) == null) {
        issues.add(
            new WorkbookIssue(
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
        issues.add(
            new WorkbookIssue(
                WF_NODE_SHEET,
                wfNodeRowNo,
                COL_RELATED_JOB_CODE,
                "related_job_code references unknown job definition: " + relatedJob));
      }
      String relatedPipeline = normalize(row.get(COL_RELATED_PIPELINE_CODE));
      if (hasText(relatedPipeline) && !pipelineJobCodesInExcel.contains(relatedPipeline)) {
        List<Map<String, Object>> found =
            pipelineDefinitionMapper.selectByQuery(
                tenantId, relatedPipeline, null, null, new PageRequest(1, 1));
        if (found == null || found.isEmpty()) {
          issues.add(
              new WorkbookIssue(
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
        issues.add(
            new WorkbookIssue(
                WF_NODE_SHEET,
                wfNodeRowNo,
                COL_WINDOW_CODE,
                "window_code references unknown batch_window: " + windowCode));
      }
      fallbackRowNo++;
    }
    return issues;
  }

  /**
   * ADR-025 §校验项清单 — Excel 导入阶段的纯静态 DAG 拓扑校验。
   *
   * <p>覆盖:V1 环/自环,V2 不可从 START 到达,V3 不可到达 END,V4 nodeParams DSL 引用了不存在的节点, V11 START 唯一/END
   * ≥1/START 无入边/END 无出边,V17 CONDITION 边必填 conditionExpr,V18 DSL 只能引用拓扑序前节点。
   *
   * <p>不覆盖(留给 enable 时 orchestrator WorkflowGraphValidator):V5/V8/V12 output contract、 V9/V10
   * GATEWAY join_mode、V15 calendar/timezone 一致性、V16 WAIT sensor_spec —— Excel 校验阶段 只拦致命拓扑问题,深 JSON
   * spec 解析放在 enable 路径降低 import 复杂度。
   */
  private List<WorkbookIssue> validateWorkflowGraphTopology(
      List<Map<String, String>> validWfNodes, List<Map<String, String>> validWfEdges) {
    Map<String, WfGraphCtx> graphs = new LinkedHashMap<>();
    for (Map<String, String> n : validWfNodes) {
      String key = wfTopoKey(n);
      graphs.computeIfAbsent(key, k -> new WfGraphCtx()).nodes.add(n);
    }
    for (Map<String, String> e : validWfEdges) {
      String key = wfTopoKey(e);
      WfGraphCtx g = graphs.get(key);
      if (g != null) {
        g.edges.add(e);
      }
      // 找不到 workflow 的 edge 已在 validateWfEdgeRow 中以 FK 失败标出,这里不重复
    }
    List<WorkbookIssue> issues = new ArrayList<>();
    for (WfGraphCtx g : graphs.values()) {
      validateWorkflowGraph(g, issues);
    }
    return issues;
  }

  private static String wfTopoKey(Map<String, String> row) {
    String v =
        Texts.hasText(row.get(COL_WORKFLOW_VERSION))
            ? normalize(row.get(COL_WORKFLOW_VERSION))
            : normalize(row.get(COL_VERSION));
    return normalize(row.get(COL_WORKFLOW_CODE)) + KEY_SEP_COLON + v;
  }

  private static final Pattern DSL_NODE_REF = Pattern.compile("\\$\\.nodes\\.([A-Za-z0-9_]+)\\.");

  // workflow graph 校验是一个完整 transaction:索引节点 -> 邻接表 -> 环/可达/终止 一气呵成,
  // 拆方法会让中间状态(byCode/incoming/outgoing/startCodes/endCodes)散落到字段,牺牲可读性。
  @SuppressWarnings("PMD.NcssCount")
  private void validateWorkflowGraph(WfGraphCtx g, List<WorkbookIssue> issues) {
    // 索引:nodeCode -> row(取首个,duplicate 已在 validateWfNodeRow 报过)
    Map<String, Map<String, String>> byCode = new LinkedHashMap<>();
    for (Map<String, String> n : g.nodes) {
      String code = normalize(n.get(COL_NODE_CODE));
      if (Texts.hasText(code)) {
        byCode.putIfAbsent(code, n);
      }
    }
    // 邻接表
    Map<String, List<String>> outgoing = new HashMap<>();
    Map<String, List<String>> incoming = new HashMap<>();
    for (Map<String, String> e : g.edges) {
      String f = normalize(e.get(COL_FROM_NODE_CODE));
      String t = normalize(e.get(COL_TO_NODE_CODE));
      if (!Texts.hasText(f) || !Texts.hasText(t)) {
        continue;
      }
      outgoing.computeIfAbsent(f, k -> new ArrayList<>()).add(t);
      incoming.computeIfAbsent(t, k -> new ArrayList<>()).add(f);
    }
    // START / END 集合
    Set<String> startCodes = new LinkedHashSet<>();
    Set<String> endCodes = new LinkedHashSet<>();
    for (Map.Entry<String, Map<String, String>> e : byCode.entrySet()) {
      String type = normalizeEnum(e.getValue().get(COL_NODE_TYPE));
      if ("START".equals(type)) {
        startCodes.add(e.getKey());
      } else if ("END".equals(type)) {
        endCodes.add(e.getKey());
      }
    }
    int fallbackRowNo = 2;
    // V11 — START 数量、END 数量、START 入边、END 出边
    if (startCodes.isEmpty() && !byCode.isEmpty()) {
      // 没有 START 节点,挑首节点定位
      Map<String, String> first = byCode.values().iterator().next();
      issues.add(
          new WorkbookIssue(
              WF_NODE_SHEET,
              excelRowNo(first, fallbackRowNo),
              COL_NODE_TYPE,
              "workflow graph missing START node"));
    }
    if (endCodes.isEmpty() && !byCode.isEmpty()) {
      Map<String, String> first = byCode.values().iterator().next();
      issues.add(
          new WorkbookIssue(
              WF_NODE_SHEET,
              excelRowNo(first, fallbackRowNo),
              COL_NODE_TYPE,
              "workflow graph requires at least 1 END node"));
    }
    if (startCodes.size() > 1) {
      for (String s : startCodes) {
        Map<String, String> n = byCode.get(s);
        issues.add(
            new WorkbookIssue(
                WF_NODE_SHEET,
                excelRowNo(n, fallbackRowNo),
                COL_NODE_TYPE,
                "workflow graph has multiple START nodes: " + startCodes));
      }
    }
    for (String s : startCodes) {
      if (!incoming.getOrDefault(s, List.of()).isEmpty()) {
        Map<String, String> n = byCode.get(s);
        issues.add(
            new WorkbookIssue(
                WF_NODE_SHEET,
                excelRowNo(n, fallbackRowNo),
                COL_NODE_TYPE,
                "START node cannot have incoming edges: " + s));
      }
    }
    for (String e : endCodes) {
      if (!outgoing.getOrDefault(e, List.of()).isEmpty()) {
        Map<String, String> n = byCode.get(e);
        issues.add(
            new WorkbookIssue(
                WF_NODE_SHEET,
                excelRowNo(n, fallbackRowNo),
                COL_NODE_TYPE,
                "END node cannot have outgoing edges: " + e));
      }
    }
    // V1 — self-loop + cycle
    int fallbackEdgeRow = 2;
    for (Map<String, String> e : g.edges) {
      String f = normalize(e.get(COL_FROM_NODE_CODE));
      String t = normalize(e.get(COL_TO_NODE_CODE));
      if (Texts.hasText(f) && f.equals(t)) {
        issues.add(
            new WorkbookIssue(
                WF_EDGE_SHEET,
                excelRowNo(e, fallbackEdgeRow),
                COL_TO_NODE_CODE,
                "self-loop edge on node: " + f));
      }
      fallbackEdgeRow++;
    }
    Map<String, Integer> color = new HashMap<>();
    for (String n : byCode.keySet()) {
      if (color.getOrDefault(n, 0) == 0) {
        dfsCycle(n, outgoing, color, byCode, fallbackRowNo, issues);
      }
    }
    // V2 — unreachable from START
    Set<String> reachFromStart = new HashSet<>();
    for (String s : startCodes) {
      dfsCollect(s, outgoing, reachFromStart);
    }
    if (!startCodes.isEmpty()) {
      for (String code : byCode.keySet()) {
        if (!reachFromStart.contains(code)) {
          Map<String, String> n = byCode.get(code);
          issues.add(
              new WorkbookIssue(
                  WF_NODE_SHEET,
                  excelRowNo(n, fallbackRowNo),
                  COL_NODE_CODE,
                  "node unreachable from START: " + code));
        }
      }
    }
    // V3 — cannot reach END
    Set<String> reachToEnd = new HashSet<>();
    for (String e : endCodes) {
      dfsCollect(e, incoming, reachToEnd);
    }
    if (!endCodes.isEmpty()) {
      for (String code : byCode.keySet()) {
        if (!reachToEnd.contains(code) && !endCodes.contains(code)) {
          Map<String, String> n = byCode.get(code);
          issues.add(
              new WorkbookIssue(
                  WF_NODE_SHEET,
                  excelRowNo(n, fallbackRowNo),
                  COL_NODE_CODE,
                  "node cannot reach any END: " + code));
        }
      }
    }
    // V17 — CONDITION edge 必填 conditionExpr
    int rowNo = 2;
    for (Map<String, String> e : g.edges) {
      String type = normalizeEnum(e.get(COL_EDGE_TYPE));
      String expr = normalize(e.get(COL_CONDITION_EXPR));
      if ("CONDITION".equals(type) && !Texts.hasText(expr)) {
        issues.add(
            new WorkbookIssue(
                WF_EDGE_SHEET,
                excelRowNo(e, rowNo),
                COL_CONDITION_EXPR,
                "CONDITION edge requires non-empty condition_expr"));
      }
      rowNo++;
    }
    // V4 / V18 — DSL refs 必须存在 & 必须是拓扑序前(ancestor)
    Map<String, Set<String>> ancestors = computeAncestors(byCode.keySet(), outgoing, startCodes);
    for (Map.Entry<String, Map<String, String>> e : byCode.entrySet()) {
      Map<String, String> n = e.getValue();
      String params = n.get(COL_NODE_PARAMS);
      if (!Texts.hasText(params)) {
        continue;
      }
      java.util.regex.Matcher m = DSL_NODE_REF.matcher(params);
      while (m.find()) {
        String ref = m.group(1);
        if (!byCode.containsKey(ref)) {
          issues.add(
              new WorkbookIssue(
                  WF_NODE_SHEET,
                  excelRowNo(n, fallbackRowNo),
                  COL_NODE_PARAMS,
                  "node_params DSL references missing node: " + ref));
          continue;
        }
        Set<String> ancSet = ancestors.getOrDefault(e.getKey(), Set.of());
        if (!ancSet.contains(ref)) {
          issues.add(
              new WorkbookIssue(
                  WF_NODE_SHEET,
                  excelRowNo(n, fallbackRowNo),
                  COL_NODE_PARAMS,
                  "node_params DSL can only reference upstream nodes; '"
                      + ref
                      + "' is not an ancestor of '"
                      + e.getKey()
                      + "'"));
        }
      }
    }
  }

  private static void dfsCycle(
      String node,
      Map<String, List<String>> outgoing,
      Map<String, Integer> color,
      Map<String, Map<String, String>> byCode,
      int fallback,
      List<WorkbookIssue> issues) {
    color.put(node, 1);
    for (String next : outgoing.getOrDefault(node, List.of())) {
      Integer c = color.getOrDefault(next, 0);
      if (c == 1) {
        Map<String, String> n = byCode.get(node);
        issues.add(
            new WorkbookIssue(
                WF_EDGE_SHEET,
                n == null ? fallback : excelRowNo(n, fallback),
                COL_TO_NODE_CODE,
                "topology cycle detected through edge: " + node + " -> " + next));
        return;
      }
      if (c == 0) {
        dfsCycle(next, outgoing, color, byCode, fallback, issues);
      }
    }
    color.put(node, 2);
  }

  private static void dfsCollect(String node, Map<String, List<String>> adj, Set<String> visited) {
    if (!visited.add(node)) {
      return;
    }
    for (String next : adj.getOrDefault(node, List.of())) {
      dfsCollect(next, adj, visited);
    }
  }

  /** 给每个节点算出可达的祖先集合。从每个 START 出发,沿 outgoing 边游走,把 source + source 的祖先并入 target 的祖先。 */
  private static Map<String, Set<String>> computeAncestors(
      Set<String> allCodes, Map<String, List<String>> outgoing, Set<String> startCodes) {
    Map<String, Set<String>> ancestors = new HashMap<>();
    for (String code : allCodes) {
      ancestors.put(code, new HashSet<>());
    }
    for (String s : startCodes) {
      walkAncestors(s, outgoing, ancestors, new HashSet<>());
    }
    return ancestors;
  }

  private static void walkAncestors(
      String node,
      Map<String, List<String>> outgoing,
      Map<String, Set<String>> ancestors,
      Set<String> visited) {
    if (!visited.add(node)) {
      return;
    }
    for (String next : outgoing.getOrDefault(node, List.of())) {
      Set<String> nextAnc = ancestors.get(next);
      if (nextAnc != null) {
        nextAnc.add(node);
        Set<String> nodeAnc = ancestors.get(node);
        if (nodeAnc != null) {
          nextAnc.addAll(nodeAnc);
        }
      }
      walkAncestors(next, outgoing, ancestors, visited);
    }
  }

  private static final class WfGraphCtx {
    final List<Map<String, String>> nodes = new ArrayList<>();
    final List<Map<String, String>> edges = new ArrayList<>();
  }

  private void addJobDependencyIssues(
      String tenantId,
      List<Map<String, String>> rows,
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
        issues.add(
            new WorkbookIssue(
                JOB_SHEET,
                rowNo,
                COL_QUEUE_CODE,
                "queue_code references unknown resource_queue: " + queueCode));
      }
      String calendarCode = normalize(row.get(COL_CALENDAR_CODE));
      if (hasText(calendarCode)
          && !calendarCodesInExcel.contains(calendarCode)
          && !businessCalendarExists(tenantId, calendarCode)) {
        issues.add(
            new WorkbookIssue(
                JOB_SHEET,
                rowNo,
                COL_CALENDAR_CODE,
                "calendar_code references unknown business_calendar: " + calendarCode));
      }
      String windowCode = normalize(row.get(COL_WINDOW_CODE));
      if (hasText(windowCode)
          && !windowCodesInExcel.contains(windowCode)
          && !batchWindowExists(tenantId, windowCode)) {
        issues.add(
            new WorkbookIssue(
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

  private static int excelRowNo(Map<String, String> row, int fallbackRowNo) {
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
      TemplateRef ref = extractTemplateRef(row.get(jsonColumn));
      if (ref.hasTemplateCode()
          && !fileTemplatesInExcel.contains(templateKey(ref.templateCode(), ref.version()))
          && !fileTemplateExists(tenantId, ref)) {
        issues.add(
            new WorkbookIssue(
                sheetName,
                rowNo,
                jsonColumn,
                "templateCode references unknown file_template_config: "
                    + templateKey(ref.templateCode(), ref.version())));
      }
      fallbackRowNo++;
    }
  }

  private static Set<String> buildFileTemplateKeys(List<Map<String, String>> rows) {
    Set<String> keys = new HashSet<>();
    for (Map<String, String> row : rows) {
      String templateCode = normalize(row.get("template_code"));
      Integer version = parseVersion(row.get(COL_VERSION));
      if (hasText(templateCode)) {
        keys.add(templateCode);
        keys.add(templateKey(templateCode, version));
      }
    }
    return keys;
  }

  private TemplateRef extractTemplateRef(String json) {
    String n = normalize(json);
    if (!hasText(n)) {
      return TemplateRef.empty();
    }
    try {
      JsonNode root = objectMapper.readTree(n);
      String templateCode = firstText(root, "templateCode", "template_code");
      Integer version = firstInt(root, "templateVersion", "template_version", "version");
      return new TemplateRef(templateCode, version);
    } catch (JsonProcessingException e) {
      return TemplateRef.empty();
    }
  }

  private static String firstText(JsonNode root, String... names) {
    for (String name : names) {
      JsonNode node = root.get(name);
      if (node != null && !node.isNull() && hasText(node.asText())) {
        return normalize(node.asText());
      }
    }
    return null;
  }

  private static Integer firstInt(JsonNode root, String... names) {
    for (String name : names) {
      JsonNode node = root.get(name);
      if (node == null || node.isNull()) {
        continue;
      }
      if (node.canConvertToInt()) {
        return node.asInt();
      }
      Integer parsed = parseVersion(node.asText());
      if (parsed != null) {
        return parsed;
      }
    }
    return null;
  }

  private boolean fileTemplateExists(String tenantId, TemplateRef ref) {
    if (ref.version() != null) {
      Map<String, Object> found =
          fileTemplateConfigMapper.selectByUniqueKey(tenantId, ref.templateCode(), ref.version());
      return found != null && !found.isEmpty();
    }
    Map<String, Object> found =
        fileTemplateConfigMapper.selectSecurityFlagsByTemplateCode(tenantId, ref.templateCode());
    return found != null && !found.isEmpty();
  }

  private static String templateKey(String templateCode, Integer version) {
    if (!hasText(templateCode)) {
      return null;
    }
    return version == null ? templateCode : templateCode + KEY_SEP_COLON + version;
  }

  private static Integer parseVersion(String value) {
    String n = normalize(value);
    if (!hasText(n)) {
      return null;
    }
    try {
      return Integer.parseInt(n);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private record TemplateRef(String templateCode, Integer version) {
    private static TemplateRef empty() {
      return new TemplateRef(null, null);
    }

    private boolean hasTemplateCode() {
      return hasText(templateCode);
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
