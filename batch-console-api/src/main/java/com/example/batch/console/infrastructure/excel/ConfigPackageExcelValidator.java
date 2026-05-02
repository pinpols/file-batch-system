package com.example.batch.console.infrastructure.excel;

import com.example.batch.common.enums.AlertSeverity;
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
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.console.mapper.JobDefinitionMapper;
import com.example.batch.console.mapper.PipelineDefinitionMapper;
import com.example.batch.console.mapper.StepRegistryQueryMapper;
import com.example.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import com.example.batch.console.support.excel.TenantConfigPackageExcelImportStore.PackageExcelSession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.example.batch.console.infrastructure.config.DefaultConsoleTenantConfigPackageExcelApplicationService;

/**
 * Validates rows parsed from the tenant config package Excel workbook. Extracted from
 * DefaultConsoleTenantConfigPackageExcelApplicationService to reduce class size.
 */
public class ConfigPackageExcelValidator {

  public static final String COL_TENANT_ID = "tenant_id";
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
  public static final String COL_ROUTE_CODE = "route_code";
  public static final String COL_ROUTE_NAME = "route_name";
  public static final String COL_TEAM = "team";
  public static final String COL_ALERT_GROUP = "alert_group";
  public static final String COL_RECEIVER = "receiver";
  public static final String COL_PIPELINE_NAME = "pipeline_name";
  public static final String COL_STEP_CODE = "step_code";
  public static final String COL_STEP_NAME = "step_name";
  public static final String COL_IMPL_CODE = "impl_code";
  public static final String COL_NODE_ORDER = "node_order";
  public static final String COL_CONDITION_EXPR = "condition_expr";

  public static final String KEY_SEP_COLON = ":";
  public static final String KEY_SEP_HASH = "#";

  public static final String JOB_SHEET = "job_definition";
  public static final String CHANNEL_SHEET = "file_channel_config";
  public static final String ROUTING_SHEET = "alert_routing_config";
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
  public static final Set<String> SEVERITIES = DictEnum.codes(AlertSeverity.class);
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

  public ConfigPackageExcelValidator(
      JobDefinitionMapper jobDefinitionMapper,
      PipelineDefinitionMapper pipelineDefinitionMapper,
      StepRegistryQueryMapper stepRegistryQueryMapper) {
    this.jobDefinitionMapper = jobDefinitionMapper;
    this.pipelineDefinitionMapper = pipelineDefinitionMapper;
    this.stepRegistryQueryMapper = stepRegistryQueryMapper;
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
      SheetResult jobs,
      SheetResult channels,
      SheetResult routings,
      SheetResult pipelines,
      SheetResult steps,
      SheetResult wfDefs,
      SheetResult wfNodes,
      SheetResult wfEdges,
      List<WorkbookIssue> crossRefIssues) {

    public int totalInvalid() {
      return jobs.invalid()
          + channels.invalid()
          + routings.invalid()
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

    public List<Map<String, String>> validChannels() {
      return channels.validRows();
    }

    public List<Map<String, String>> validRoutings() {
      return routings.validRows();
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
      all.addAll(jobs.issues());
      all.addAll(channels.issues());
      all.addAll(routings.issues());
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
    SheetResult jobs = validateJobRows(tid, session.jobRows());
    SheetResult channels = validateChannelRows(tid, session.fileChannelRows());
    SheetResult routings = validateRoutingRows(tid, session.alertRoutingRows());
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
            jobs.validRows(),
            pipelines.validRows(),
            wfNodes.validRows(),
            session.pipelineRows());
    return new PackageValidationResult(
        jobs, channels, routings, pipelines, steps, wfDefs, wfNodes, wfEdges, crossIssues);
  }

  private SheetResult validateJobRows(String tenantId, List<Map<String, String>> rows) {
    List<WorkbookIssue> issues = new ArrayList<>();
    List<Map<String, String>> valid = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int rowNo = 2;
    for (Map<String, String> row : rows) {
      List<String> ri = new ArrayList<>();
      String jobCode = normalize(row.get(COL_JOB_CODE));
      if (!hasText(jobCode)) {
        ri.add("job_code is required");
      }
      if (!hasText(normalize(row.get(COL_JOB_NAME)))) {
        ri.add("job_name is required");
      }
      String jobType = normalizeEnum(row.get(COL_JOB_TYPE));
      if (!hasText(jobType)) {
        ri.add("job_type is required");
      } else if (!JOB_TYPES.contains(jobType)) {
        ri.add("job_type must be one of " + JOB_TYPES);
      }
      String scheduleType = normalizeEnum(row.get(COL_SCHEDULE_TYPE));
      if (!hasText(scheduleType)) {
        ri.add("schedule_type is required");
      } else if (!SCHEDULE_TYPES.contains(scheduleType)) {
        ri.add("schedule_type must be one of " + SCHEDULE_TYPES);
      }
      String retryPolicy = normalizeEnum(row.get(COL_RETRY_POLICY));
      if (hasText(retryPolicy) && !RETRY_POLICIES.contains(retryPolicy)) {
        ri.add("retry_policy must be one of " + RETRY_POLICIES);
      }
      String shardStrategy = normalizeEnum(row.get(COL_SHARD_STRATEGY));
      if (hasText(shardStrategy) && !SHARD_STRATEGIES.contains(shardStrategy)) {
        ri.add("shard_strategy must be one of " + SHARD_STRATEGIES);
      }
      String paramSchema = row.get(COL_PARAM_SCHEMA);
      if (hasText(paramSchema)) {
        try {
          JsonUtils.fromJson(paramSchema, Object.class);
        } catch (Exception e) {
          ri.add("param_schema must be valid JSON");
        }
      }
      if (hasText(jobCode) && !seen.add(tenantId + KEY_SEP_HASH + jobCode)) {
        ri.add("duplicate job_code in excel: " + jobCode);
      }
      addIssues(ri, JOB_SHEET, rowNo, issues);
      if (ri.isEmpty()) {
        valid.add(row);
      }
      rowNo++;
    }
    return new SheetResult(JOB_SHEET, rows.size(), valid, issues);
  }

  private SheetResult validateChannelRows(String tenantId, List<Map<String, String>> rows) {
    List<WorkbookIssue> issues = new ArrayList<>();
    List<Map<String, String>> valid = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int rowNo = 2;
    for (Map<String, String> row : rows) {
      List<String> ri = new ArrayList<>();
      String code = normalize(row.get(COL_CHANNEL_CODE));
      if (!hasText(code)) {
        ri.add("channel_code is required");
      }
      if (!hasText(normalize(row.get(COL_CHANNEL_NAME)))) {
        ri.add("channel_name is required");
      }
      String channelType = normalizeEnum(row.get(COL_CHANNEL_TYPE));
      if (!hasText(channelType)) {
        ri.add("channel_type is required");
      } else if (!CHANNEL_TYPES.contains(channelType)) {
        ri.add("channel_type must be one of " + CHANNEL_TYPES);
      }
      String authType = normalizeEnum(row.get(COL_AUTH_TYPE));
      if (!hasText(authType)) {
        ri.add("auth_type is required");
      } else if (!AUTH_TYPES.contains(authType)) {
        ri.add("auth_type must be one of " + AUTH_TYPES);
      }
      String receiptPolicy = normalizeEnum(row.get(COL_RECEIPT_POLICY));
      if (!hasText(receiptPolicy)) {
        ri.add("receipt_policy is required");
      } else if (!RECEIPT_POLICIES.contains(receiptPolicy)) {
        ri.add("receipt_policy must be one of " + RECEIPT_POLICIES);
      }
      String configJson = row.get(COL_CONFIG_JSON);
      if (!hasText(configJson)) {
        ri.add("config_json is required");
      } else {
        try {
          JsonUtils.fromJson(configJson, Object.class);
        } catch (Exception e) {
          ri.add("config_json must be valid JSON");
        }
      }
      if (hasText(code) && !seen.add(tenantId + KEY_SEP_HASH + code)) {
        ri.add("duplicate channel_code in excel: " + code);
      }
      addIssues(ri, CHANNEL_SHEET, rowNo, issues);
      if (ri.isEmpty()) {
        valid.add(row);
      }
      rowNo++;
    }
    return new SheetResult(CHANNEL_SHEET, rows.size(), valid, issues);
  }

  private SheetResult validateRoutingRows(String tenantId, List<Map<String, String>> rows) {
    List<WorkbookIssue> issues = new ArrayList<>();
    List<Map<String, String>> valid = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int rowNo = 2;
    for (Map<String, String> row : rows) {
      List<String> ri = new ArrayList<>();
      String code = normalize(row.get(COL_ROUTE_CODE));
      if (!hasText(code)) {
        ri.add("route_code is required");
      }
      if (!hasText(normalize(row.get(COL_ROUTE_NAME)))) {
        ri.add("route_name is required");
      }
      if (!hasText(normalize(row.get(COL_TEAM)))) {
        ri.add("team is required");
      }
      if (!hasText(normalize(row.get(COL_ALERT_GROUP)))) {
        ri.add("alert_group is required");
      }
      String severity = normalizeEnum(row.get(COL_SEVERITY));
      if (!hasText(severity)) {
        ri.add("severity is required");
      } else if (!SEVERITIES.contains(severity)) {
        ri.add("severity must be one of " + SEVERITIES);
      }
      if (!hasText(normalize(row.get(COL_RECEIVER)))) {
        ri.add("receiver is required");
      }
      if (hasText(code) && !seen.add(tenantId + KEY_SEP_HASH + code)) {
        ri.add("duplicate route_code in excel: " + code);
      }
      addIssues(ri, ROUTING_SHEET, rowNo, issues);
      if (ri.isEmpty()) {
        valid.add(row);
      }
      rowNo++;
    }
    return new SheetResult(ROUTING_SHEET, rows.size(), valid, issues);
  }

  private SheetResult validatePipelineRows(String tenantId, List<Map<String, String>> rows) {
    List<WorkbookIssue> issues = new ArrayList<>();
    List<Map<String, String>> valid = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int rowNo = 2;
    for (Map<String, String> row : rows) {
      List<String> ri = new ArrayList<>();
      String jobCode = normalize(row.get(COL_JOB_CODE));
      String version = normalize(row.get(COL_VERSION));
      if (!hasText(jobCode)) {
        ri.add("job_code is required");
      }
      if (!hasText(normalize(row.get(COL_PIPELINE_NAME)))) {
        ri.add("pipeline_name is required");
      }
      String pType = normalizeEnum(row.get(COL_PIPELINE_TYPE));
      if (!hasText(pType)) {
        ri.add("pipeline_type is required");
      } else if (!PIPELINE_TYPES.contains(pType)) {
        ri.add("pipeline_type must be one of " + PIPELINE_TYPES);
      }
      if (!hasText(version)) {
        ri.add("version is required");
      } else {
        try {
          Integer.parseInt(version);
        } catch (NumberFormatException e) {
          ri.add("version must be integer");
        }
      }
      if (hasText(jobCode)
          && hasText(version)
          && !seen.add(tenantId + KEY_SEP_HASH + jobCode + KEY_SEP_COLON + version)) {
        ri.add("duplicate pipeline key (job_code + version): " + jobCode + KEY_SEP_COLON + version);
      }
      addIssues(ri, PIPELINE_SHEET, rowNo, issues);
      if (ri.isEmpty()) {
        valid.add(row);
      }
      rowNo++;
    }
    return new SheetResult(PIPELINE_SHEET, rows.size(), valid, issues);
  }

  private SheetResult validateStepRows(
      List<Map<String, String>> rows, List<Map<String, String>> validPipelineRows) {
    Set<String> pipelineKeys =
        validPipelineRows.stream()
            .map(
                r -> normalize(r.get(COL_JOB_CODE)) + KEY_SEP_COLON + normalize(r.get(COL_VERSION)))
            .collect(Collectors.toSet());
    // pipelineKey → pipeline_type（IMPORT / EXPORT / DISPATCH）；step 校验 impl_code 时按此定位模块
    Map<String, String> pipelineKeyToType = new HashMap<>();
    for (Map<String, String> p : validPipelineRows) {
      String key = normalize(p.get(COL_JOB_CODE)) + KEY_SEP_COLON + normalize(p.get(COL_VERSION));
      String type = normalizeEnum(p.get(COL_PIPELINE_TYPE));
      if (hasText(type)) {
        pipelineKeyToType.put(key, type);
      }
    }
    // 按模块懒加载 step_registry 白名单；空集表示该 module 的 worker 未启动过登记，降级为不校验
    // （防止首次部署没跑 worker 就导致所有上传被拒）
    Map<String, Set<String>> registryByModule = new HashMap<>();
    List<WorkbookIssue> issues = new ArrayList<>();
    List<Map<String, String>> valid = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int rowNo = 2;
    for (Map<String, String> row : rows) {
      List<String> ri = new ArrayList<>();
      String jobCode = normalize(row.get(COL_JOB_CODE));
      String version = normalize(row.get(COL_VERSION));
      String stepCode = normalize(row.get(COL_STEP_CODE));
      String implCode = normalize(row.get(COL_IMPL_CODE));
      if (!hasText(jobCode)) {
        ri.add("job_code is required");
      }
      if (!hasText(version)) {
        ri.add("version is required");
      }
      if (!hasText(stepCode)) {
        ri.add("step_code is required");
      }
      if (!hasText(normalize(row.get(COL_STEP_NAME)))) {
        ri.add("step_name is required");
      }
      String stageCode = normalizeEnum(row.get(COL_STAGE_CODE));
      // pipelineKey 在后面定义，这里先算一个本地副本用于 stage/impl 联动校验
      String stagePipelineKey = jobCode + KEY_SEP_COLON + version;
      if (!hasText(stageCode)) {
        ri.add("stage_code is required");
      } else if (!STAGE_CODES.contains(stageCode)) {
        ri.add("stage_code must be one of " + STAGE_CODES);
      } else if (pipelineKeyToType.containsKey(stagePipelineKey)) {
        // 按 pipeline_type 做精确校验：例如 EXPORT 管线不能出现 PREPROCESS/LOAD 这种 IMPORT stage
        String pipelineType = pipelineKeyToType.get(stagePipelineKey);
        Set<String> allowed = STAGES_BY_TYPE.get(pipelineType);
        if (allowed != null && !allowed.contains(stageCode)) {
          ri.add(
              "stage_code '"
                  + stageCode
                  + "' 不属于 pipeline_type '"
                  + pipelineType
                  + "'，允许值："
                  + allowed);
        }
      }
      String retryPolicy = normalizeEnum(row.get(COL_RETRY_POLICY));
      if (hasText(retryPolicy) && !RETRY_POLICIES.contains(retryPolicy)) {
        ri.add("retry_policy must be one of " + RETRY_POLICIES);
      }
      String pipelineKey = jobCode + KEY_SEP_COLON + version;
      if (hasText(jobCode) && hasText(version) && !pipelineKeys.contains(pipelineKey)) {
        ri.add("no matching pipeline for job_code + version: " + pipelineKey);
      }
      if (hasText(jobCode)
          && hasText(version)
          && hasText(stepCode)
          && !seen.add(pipelineKey + KEY_SEP_HASH + stepCode)) {
        ri.add("duplicate step_code in pipeline: " + stepCode);
      }
      validateImplCode(row, implCode, pipelineKey, pipelineKeyToType, registryByModule, ri);

      // 业务表/列精确校验的"硬拦截"故意不放在这里——Validator 只做 Excel 格式 + 枚举 / registry
      // 层面的校验，不耦合业务 schema。biz_table_schema 的信息通过模板下拉在 ConfigPackageExcelWorkbookWriter
      // 里以下拉选项形式呈现给填表用户；真正的 schema 漂移由 LoadStep 在运行时报业务错。
      addIssues(ri, STEP_SHEET, rowNo, issues);
      if (ri.isEmpty()) {
        valid.add(row);
      }
      rowNo++;
    }
    return new SheetResult(STEP_SHEET, rows.size(), valid, issues);
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
      String wfCode = normalize(row.get(COL_WORKFLOW_CODE));
      String version = normalize(row.get(COL_VERSION));
      if (!hasText(wfCode)) {
        ri.add("workflow_code is required");
      }
      if (!hasText(normalize(row.get(COL_WORKFLOW_NAME)))) {
        ri.add("workflow_name is required");
      }
      String wfType = normalizeEnum(row.get(COL_WORKFLOW_TYPE));
      if (!hasText(wfType)) {
        ri.add("workflow_type is required");
      } else if (!WORKFLOW_TYPES.contains(wfType)) {
        ri.add("workflow_type must be one of " + WORKFLOW_TYPES);
      }
      if (!hasText(version)) {
        ri.add("version is required");
      } else {
        try {
          Integer.parseInt(version);
        } catch (NumberFormatException e) {
          ri.add("version must be integer");
        }
      }
      if (hasText(wfCode)
          && hasText(version)
          && !seen.add(tenantId + KEY_SEP_HASH + wfCode + KEY_SEP_COLON + version)) {
        ri.add("duplicate workflow definition: " + wfCode + KEY_SEP_COLON + version);
      }
      addIssues(ri, WF_DEF_SHEET, rowNo, issues);
      if (ri.isEmpty()) {
        valid.add(row);
      }
      rowNo++;
    }
    return new SheetResult(WF_DEF_SHEET, rows.size(), valid, issues);
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
      String wfCode = normalize(row.get(COL_WORKFLOW_CODE));
      String wfVersion = normalize(row.get(COL_WORKFLOW_VERSION));
      String nodeCode = normalize(row.get(COL_NODE_CODE));
      if (!hasText(wfCode)) {
        ri.add("workflow_code is required");
      }
      if (!hasText(wfVersion)) {
        ri.add("workflow_version is required");
      }
      if (!hasText(nodeCode)) {
        ri.add("node_code is required");
      }
      if (!hasText(normalize(row.get(COL_NODE_NAME)))) {
        ri.add("node_name is required");
      }
      String nodeType = normalizeEnum(row.get(COL_NODE_TYPE));
      if (!hasText(nodeType)) {
        ri.add("node_type is required");
      } else if (!NODE_TYPES.contains(nodeType)) {
        ri.add("node_type must be one of " + NODE_TYPES);
      }
      String retryPolicy = normalizeEnum(row.get(COL_RETRY_POLICY));
      if (hasText(retryPolicy) && !RETRY_POLICIES.contains(retryPolicy)) {
        ri.add("retry_policy must be one of " + RETRY_POLICIES);
      }
      String nodeParams = row.get(COL_NODE_PARAMS);
      if (hasText(nodeParams)) {
        try {
          JsonUtils.fromJson(nodeParams, Object.class);
        } catch (Exception e) {
          ri.add("node_params must be valid JSON");
        }
      }
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
      addIssues(ri, WF_NODE_SHEET, rowNo, issues);
      if (ri.isEmpty()) {
        valid.add(row);
      }
      rowNo++;
    }
    return new SheetResult(WF_NODE_SHEET, rows.size(), valid, issues);
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
      String wfCode = normalize(row.get(COL_WORKFLOW_CODE));
      String wfVersion = normalize(row.get(COL_WORKFLOW_VERSION));
      String fromNode = normalize(row.get(COL_FROM_NODE_CODE));
      String toNode = normalize(row.get(COL_TO_NODE_CODE));
      if (!hasText(wfCode)) {
        ri.add("workflow_code is required");
      }
      if (!hasText(wfVersion)) {
        ri.add("workflow_version is required");
      }
      if (!hasText(fromNode)) {
        ri.add("from_node_code is required");
      }
      if (!hasText(toNode)) {
        ri.add("to_node_code is required");
      }
      String edgeType = normalizeEnum(row.get(COL_EDGE_TYPE));
      if (!hasText(edgeType)) {
        ri.add("edge_type is required");
      } else if (!EDGE_TYPES.contains(edgeType)) {
        ri.add("edge_type must be one of " + EDGE_TYPES);
      }
      String wfKey = wfCode + KEY_SEP_COLON + wfVersion;
      if (hasText(wfCode) && hasText(wfVersion) && !wfKeys.contains(wfKey)) {
        ri.add("workflow edge references missing definition: " + wfKey);
      }
      if (hasText(wfCode)
          && hasText(wfVersion)
          && hasText(fromNode)
          && !nodeKeys.contains(wfKey + KEY_SEP_HASH + fromNode)) {
        ri.add("from_node_code references unknown node: " + fromNode);
      }
      if (hasText(wfCode)
          && hasText(wfVersion)
          && hasText(toNode)
          && !nodeKeys.contains(wfKey + KEY_SEP_HASH + toNode)) {
        ri.add("to_node_code references unknown node: " + toNode);
      }
      addIssues(ri, WF_EDGE_SHEET, rowNo, issues);
      if (ri.isEmpty()) {
        valid.add(row);
      }
      rowNo++;
    }
    return new SheetResult(WF_EDGE_SHEET, rows.size(), valid, issues);
  }

  private List<WorkbookIssue> validateCrossReferences(
      String tenantId,
      List<Map<String, String>> validJobs,
      List<Map<String, String>> validPipelines,
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
    List<WorkbookIssue> issues = new ArrayList<>();

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

    rowNo = 2;
    for (Map<String, String> row : validWfNodes) {
      String relatedJob = normalize(row.get(COL_RELATED_JOB_CODE));
      if (hasText(relatedJob)
          && !jobCodesInExcel.contains(relatedJob)
          && jobDefinitionMapper.selectByUniqueKey(tenantId, relatedJob) == null) {
        issues.add(
            new WorkbookIssue(
                WF_NODE_SHEET,
                rowNo,
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
                  rowNo,
                  COL_RELATED_PIPELINE_CODE,
                  "related_pipeline_code references unknown pipeline: " + relatedPipeline));
        }
      }
      rowNo++;
    }
    return issues;
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
