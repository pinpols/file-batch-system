package com.example.batch.console.infrastructure;

import com.example.batch.common.enums.AlertSeverity;
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
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.mapper.JobDefinitionMapper;
import com.example.batch.console.mapper.PipelineDefinitionMapper;
import com.example.batch.console.support.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import com.example.batch.console.support.TenantConfigPackageExcelImportStore.PackageExcelSession;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;

/**
 * Validates rows parsed from the tenant config package Excel workbook. Extracted from
 * DefaultConsoleTenantConfigPackageExcelApplicationService to reduce class size.
 */
class ConfigPackageExcelValidator {

  // ── column name constants (package-private, shared with main service) ──────
  static final String COL_TENANT_ID = "tenant_id";
  static final String COL_ENABLED = "enabled";
  static final String COL_DESCRIPTION = "description";
  static final String COL_VERSION = "version";
  static final String COL_BIZ_TYPE = "biz_type";
  static final String COL_WORKER_GROUP = "worker_group";
  static final String COL_WINDOW_CODE = "window_code";
  static final String COL_RETRY_POLICY = "retry_policy";
  static final String COL_RETRY_MAX_COUNT = "retry_max_count";
  static final String COL_TIMEOUT_SECONDS = "timeout_seconds";
  static final String COL_SHARD_STRATEGY = "shard_strategy";
  static final String COL_JOB_CODE = "job_code";
  static final String COL_JOB_NAME = "job_name";
  static final String COL_JOB_TYPE = "job_type";
  static final String COL_SCHEDULE_TYPE = "schedule_type";
  static final String COL_SCHEDULE_EXPR = "schedule_expr";
  static final String COL_CALENDAR_CODE = "calendar_code";
  static final String COL_QUEUE_CODE = "queue_code";
  static final String COL_PARAM_SCHEMA = "param_schema";
  static final String COL_CHANNEL_TYPE = "channel_type";
  static final String COL_AUTH_TYPE = "auth_type";
  static final String COL_RECEIPT_POLICY = "receipt_policy";
  static final String COL_SEVERITY = "severity";
  static final String COL_PIPELINE_TYPE = "pipeline_type";
  static final String COL_STAGE_CODE = "stage_code";
  static final String COL_WORKFLOW_CODE = "workflow_code";
  static final String COL_WORKFLOW_NAME = "workflow_name";
  static final String COL_WORKFLOW_TYPE = "workflow_type";
  static final String COL_WORKFLOW_VERSION = "workflow_version";
  static final String COL_NODE_CODE = "node_code";
  static final String COL_NODE_NAME = "node_name";
  static final String COL_NODE_TYPE = "node_type";
  static final String COL_RELATED_JOB_CODE = "related_job_code";
  static final String COL_RELATED_PIPELINE_CODE = "related_pipeline_code";
  static final String COL_NODE_PARAMS = "node_params";
  static final String COL_EDGE_TYPE = "edge_type";
  static final String COL_FROM_NODE_CODE = "from_node_code";
  static final String COL_TO_NODE_CODE = "to_node_code";
  static final String COL_EXECUTION_HANDLER = "execution_handler";
  static final String COL_DEFAULT_PARAMS = "default_params";
  static final String COL_CHANNEL_CODE = "channel_code";
  static final String COL_CHANNEL_NAME = "channel_name";
  static final String COL_CONFIG_JSON = "config_json";
  static final String COL_ROUTE_CODE = "route_code";
  static final String COL_ROUTE_NAME = "route_name";
  static final String COL_TEAM = "team";
  static final String COL_ALERT_GROUP = "alert_group";
  static final String COL_RECEIVER = "receiver";
  static final String COL_PIPELINE_NAME = "pipeline_name";
  static final String COL_STEP_CODE = "step_code";
  static final String COL_STEP_NAME = "step_name";
  static final String COL_NODE_ORDER = "node_order";
  static final String COL_CONDITION_EXPR = "condition_expr";

  static final String KEY_SEP_COLON = ":";
  static final String KEY_SEP_HASH = "#";

  // ── sheet names ───────────────────────────────────────────────────────────
  static final String JOB_SHEET = "job_definition";
  static final String CHANNEL_SHEET = "file_channel_config";
  static final String ROUTING_SHEET = "alert_routing_config";
  static final String PIPELINE_SHEET = "pipeline_definition";
  static final String STEP_SHEET = "pipeline_step_definition";
  static final String WF_DEF_SHEET = "workflow_definition";
  static final String WF_NODE_SHEET = "workflow_node";
  static final String WF_EDGE_SHEET = "workflow_edge";

  // ── enum sets ─────────────────────────────────────────────────────────────
  static final Set<String> JOB_TYPES = JobType.codes();
  static final Set<String> SCHEDULE_TYPES = Set.of("CRON", "FIXED_RATE", "MANUAL");
  static final Set<String> RETRY_POLICIES = RetryPolicyType.codes();
  static final Set<String> SHARD_STRATEGIES = ShardStrategy.codes();
  static final Set<String> CHANNEL_TYPES = FileChannelType.codes();
  static final Set<String> AUTH_TYPES = FileChannelAuthType.codes();
  static final Set<String> RECEIPT_POLICIES = FileReceiptPolicy.codes();
  static final Set<String> SEVERITIES = AlertSeverity.codes();
  static final Set<String> PIPELINE_TYPES = PipelineType.codes();
  static final Set<String> STAGE_CODES =
      Set.of(
          "RECEIVE", "PREPROCESS", "PARSE", "VALIDATE", "LOAD", "GENERATE", "TRANSFER",
          "DISPATCH", "ACK");
  static final Set<String> WORKFLOW_TYPES = WorkflowType.codes();
  static final Set<String> NODE_TYPES = WorkflowNodeType.codes();
  static final Set<String> EDGE_TYPES = WorkflowEdgeType.codes();

  private final JobDefinitionMapper jobDefinitionMapper;
  private final PipelineDefinitionMapper pipelineDefinitionMapper;

  ConfigPackageExcelValidator(
      JobDefinitionMapper jobDefinitionMapper,
      PipelineDefinitionMapper pipelineDefinitionMapper) {
    this.jobDefinitionMapper = jobDefinitionMapper;
    this.pipelineDefinitionMapper = pipelineDefinitionMapper;
  }

  // ── inner records ─────────────────────────────────────────────────────────

  record SheetResult(
      String sheetName,
      int total,
      List<Map<String, String>> validRows,
      List<WorkbookIssue> issues) {
    int valid() {
      return validRows.size();
    }

    int invalid() {
      return total - validRows.size();
    }
  }

  record PackageValidationResult(
      SheetResult jobs,
      SheetResult channels,
      SheetResult routings,
      SheetResult pipelines,
      SheetResult steps,
      SheetResult wfDefs,
      SheetResult wfNodes,
      SheetResult wfEdges,
      List<WorkbookIssue> crossRefIssues) {

    int totalInvalid() {
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

    List<Map<String, String>> validJobs() {
      return jobs.validRows();
    }

    List<Map<String, String>> validChannels() {
      return channels.validRows();
    }

    List<Map<String, String>> validRoutings() {
      return routings.validRows();
    }

    List<Map<String, String>> validPipelines() {
      return pipelines.validRows();
    }

    List<Map<String, String>> validSteps() {
      return steps.validRows();
    }

    List<Map<String, String>> validWfDefs() {
      return wfDefs.validRows();
    }

    List<Map<String, String>> validWfNodes() {
      return wfNodes.validRows();
    }

    List<Map<String, String>> validWfEdges() {
      return wfEdges.validRows();
    }

    List<WorkbookIssue> allIssues() {
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

  // ── orchestration ─────────────────────────────────────────────────────────

  PackageValidationResult validate(PackageExcelSession session) {
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

  // ── per-sheet validators ──────────────────────────────────────────────────

  private SheetResult validateJobRows(String tenantId, List<Map<String, String>> rows) {
    List<WorkbookIssue> issues = new ArrayList<>();
    List<Map<String, String>> valid = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int rowNo = 2;
    for (Map<String, String> row : rows) {
      List<String> ri = new ArrayList<>();
      String jobCode = normalize(row.get(COL_JOB_CODE));
      if (!hasText(jobCode)) ri.add("job_code is required");
      if (!hasText(normalize(row.get(COL_JOB_NAME)))) ri.add("job_name is required");
      String jobType = normalizeEnum(row.get(COL_JOB_TYPE));
      if (!hasText(jobType)) ri.add("job_type is required");
      else if (!JOB_TYPES.contains(jobType)) ri.add("job_type must be one of " + JOB_TYPES);
      String scheduleType = normalizeEnum(row.get(COL_SCHEDULE_TYPE));
      if (!hasText(scheduleType)) ri.add("schedule_type is required");
      else if (!SCHEDULE_TYPES.contains(scheduleType))
        ri.add("schedule_type must be one of " + SCHEDULE_TYPES);
      String retryPolicy = normalizeEnum(row.get(COL_RETRY_POLICY));
      if (hasText(retryPolicy) && !RETRY_POLICIES.contains(retryPolicy))
        ri.add("retry_policy must be one of " + RETRY_POLICIES);
      String shardStrategy = normalizeEnum(row.get(COL_SHARD_STRATEGY));
      if (hasText(shardStrategy) && !SHARD_STRATEGIES.contains(shardStrategy))
        ri.add("shard_strategy must be one of " + SHARD_STRATEGIES);
      String paramSchema = row.get(COL_PARAM_SCHEMA);
      if (hasText(paramSchema)) {
        try {
          JsonUtils.fromJson(paramSchema, Object.class);
        } catch (Exception e) {
          ri.add("param_schema must be valid JSON");
        }
      }
      if (hasText(jobCode) && !seen.add(tenantId + KEY_SEP_HASH + jobCode))
        ri.add("duplicate job_code in excel: " + jobCode);
      addIssues(ri, JOB_SHEET, rowNo, issues);
      if (ri.isEmpty()) valid.add(row);
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
      if (!hasText(code)) ri.add("channel_code is required");
      if (!hasText(normalize(row.get(COL_CHANNEL_NAME)))) ri.add("channel_name is required");
      String channelType = normalizeEnum(row.get(COL_CHANNEL_TYPE));
      if (!hasText(channelType)) ri.add("channel_type is required");
      else if (!CHANNEL_TYPES.contains(channelType))
        ri.add("channel_type must be one of " + CHANNEL_TYPES);
      String authType = normalizeEnum(row.get(COL_AUTH_TYPE));
      if (!hasText(authType)) ri.add("auth_type is required");
      else if (!AUTH_TYPES.contains(authType)) ri.add("auth_type must be one of " + AUTH_TYPES);
      String receiptPolicy = normalizeEnum(row.get(COL_RECEIPT_POLICY));
      if (!hasText(receiptPolicy)) ri.add("receipt_policy is required");
      else if (!RECEIPT_POLICIES.contains(receiptPolicy))
        ri.add("receipt_policy must be one of " + RECEIPT_POLICIES);
      String configJson = row.get(COL_CONFIG_JSON);
      if (!hasText(configJson)) ri.add("config_json is required");
      else {
        try {
          JsonUtils.fromJson(configJson, Object.class);
        } catch (Exception e) {
          ri.add("config_json must be valid JSON");
        }
      }
      if (hasText(code) && !seen.add(tenantId + KEY_SEP_HASH + code))
        ri.add("duplicate channel_code in excel: " + code);
      addIssues(ri, CHANNEL_SHEET, rowNo, issues);
      if (ri.isEmpty()) valid.add(row);
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
      if (!hasText(code)) ri.add("route_code is required");
      if (!hasText(normalize(row.get(COL_ROUTE_NAME)))) ri.add("route_name is required");
      if (!hasText(normalize(row.get(COL_TEAM)))) ri.add("team is required");
      if (!hasText(normalize(row.get(COL_ALERT_GROUP)))) ri.add("alert_group is required");
      String severity = normalizeEnum(row.get(COL_SEVERITY));
      if (!hasText(severity)) ri.add("severity is required");
      else if (!SEVERITIES.contains(severity)) ri.add("severity must be one of " + SEVERITIES);
      if (!hasText(normalize(row.get(COL_RECEIVER)))) ri.add("receiver is required");
      if (hasText(code) && !seen.add(tenantId + KEY_SEP_HASH + code))
        ri.add("duplicate route_code in excel: " + code);
      addIssues(ri, ROUTING_SHEET, rowNo, issues);
      if (ri.isEmpty()) valid.add(row);
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
      if (!hasText(jobCode)) ri.add("job_code is required");
      if (!hasText(normalize(row.get(COL_PIPELINE_NAME)))) ri.add("pipeline_name is required");
      String pType = normalizeEnum(row.get(COL_PIPELINE_TYPE));
      if (!hasText(pType)) ri.add("pipeline_type is required");
      else if (!PIPELINE_TYPES.contains(pType))
        ri.add("pipeline_type must be one of " + PIPELINE_TYPES);
      if (!hasText(version)) ri.add("version is required");
      else {
        try {
          Integer.parseInt(version);
        } catch (NumberFormatException e) {
          ri.add("version must be integer");
        }
      }
      if (hasText(jobCode)
          && hasText(version)
          && !seen.add(tenantId + KEY_SEP_HASH + jobCode + KEY_SEP_COLON + version))
        ri.add("duplicate pipeline key (job_code + version): " + jobCode + KEY_SEP_COLON + version);
      addIssues(ri, PIPELINE_SHEET, rowNo, issues);
      if (ri.isEmpty()) valid.add(row);
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
    List<WorkbookIssue> issues = new ArrayList<>();
    List<Map<String, String>> valid = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int rowNo = 2;
    for (Map<String, String> row : rows) {
      List<String> ri = new ArrayList<>();
      String jobCode = normalize(row.get(COL_JOB_CODE));
      String version = normalize(row.get(COL_VERSION));
      String stepCode = normalize(row.get(COL_STEP_CODE));
      if (!hasText(jobCode)) ri.add("job_code is required");
      if (!hasText(version)) ri.add("version is required");
      if (!hasText(stepCode)) ri.add("step_code is required");
      if (!hasText(normalize(row.get(COL_STEP_NAME)))) ri.add("step_name is required");
      String stageCode = normalizeEnum(row.get(COL_STAGE_CODE));
      if (!hasText(stageCode)) ri.add("stage_code is required");
      else if (!STAGE_CODES.contains(stageCode))
        ri.add("stage_code must be one of " + STAGE_CODES);
      String retryPolicy = normalizeEnum(row.get(COL_RETRY_POLICY));
      if (hasText(retryPolicy) && !RETRY_POLICIES.contains(retryPolicy))
        ri.add("retry_policy must be one of " + RETRY_POLICIES);
      String pipelineKey = jobCode + KEY_SEP_COLON + version;
      if (hasText(jobCode) && hasText(version) && !pipelineKeys.contains(pipelineKey))
        ri.add("no matching pipeline for job_code + version: " + pipelineKey);
      if (hasText(jobCode)
          && hasText(version)
          && hasText(stepCode)
          && !seen.add(pipelineKey + KEY_SEP_HASH + stepCode))
        ri.add("duplicate step_code in pipeline: " + stepCode);
      addIssues(ri, STEP_SHEET, rowNo, issues);
      if (ri.isEmpty()) valid.add(row);
      rowNo++;
    }
    return new SheetResult(STEP_SHEET, rows.size(), valid, issues);
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
      if (!hasText(wfCode)) ri.add("workflow_code is required");
      if (!hasText(normalize(row.get(COL_WORKFLOW_NAME)))) ri.add("workflow_name is required");
      String wfType = normalizeEnum(row.get(COL_WORKFLOW_TYPE));
      if (!hasText(wfType)) ri.add("workflow_type is required");
      else if (!WORKFLOW_TYPES.contains(wfType))
        ri.add("workflow_type must be one of " + WORKFLOW_TYPES);
      if (!hasText(version)) ri.add("version is required");
      else {
        try {
          Integer.parseInt(version);
        } catch (NumberFormatException e) {
          ri.add("version must be integer");
        }
      }
      if (hasText(wfCode)
          && hasText(version)
          && !seen.add(tenantId + KEY_SEP_HASH + wfCode + KEY_SEP_COLON + version))
        ri.add("duplicate workflow definition: " + wfCode + KEY_SEP_COLON + version);
      addIssues(ri, WF_DEF_SHEET, rowNo, issues);
      if (ri.isEmpty()) valid.add(row);
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
      if (!hasText(wfCode)) ri.add("workflow_code is required");
      if (!hasText(wfVersion)) ri.add("workflow_version is required");
      if (!hasText(nodeCode)) ri.add("node_code is required");
      if (!hasText(normalize(row.get(COL_NODE_NAME)))) ri.add("node_name is required");
      String nodeType = normalizeEnum(row.get(COL_NODE_TYPE));
      if (!hasText(nodeType)) ri.add("node_type is required");
      else if (!NODE_TYPES.contains(nodeType)) ri.add("node_type must be one of " + NODE_TYPES);
      String retryPolicy = normalizeEnum(row.get(COL_RETRY_POLICY));
      if (hasText(retryPolicy) && !RETRY_POLICIES.contains(retryPolicy))
        ri.add("retry_policy must be one of " + RETRY_POLICIES);
      String nodeParams = row.get(COL_NODE_PARAMS);
      if (hasText(nodeParams)) {
        try {
          JsonUtils.fromJson(nodeParams, Object.class);
        } catch (Exception e) {
          ri.add("node_params must be valid JSON");
        }
      }
      String wfKey = wfCode + KEY_SEP_COLON + wfVersion;
      if (hasText(wfCode) && hasText(wfVersion) && !wfKeys.contains(wfKey))
        ri.add("workflow node references missing definition: " + wfKey);
      if (hasText(wfCode)
          && hasText(wfVersion)
          && hasText(nodeCode)
          && !seen.add(wfKey + KEY_SEP_HASH + nodeCode))
        ri.add("duplicate node_code in workflow: " + nodeCode);
      addIssues(ri, WF_NODE_SHEET, rowNo, issues);
      if (ri.isEmpty()) valid.add(row);
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
      if (!hasText(wfCode)) ri.add("workflow_code is required");
      if (!hasText(wfVersion)) ri.add("workflow_version is required");
      if (!hasText(fromNode)) ri.add("from_node_code is required");
      if (!hasText(toNode)) ri.add("to_node_code is required");
      String edgeType = normalizeEnum(row.get(COL_EDGE_TYPE));
      if (!hasText(edgeType)) ri.add("edge_type is required");
      else if (!EDGE_TYPES.contains(edgeType)) ri.add("edge_type must be one of " + EDGE_TYPES);
      String wfKey = wfCode + KEY_SEP_COLON + wfVersion;
      if (hasText(wfCode) && hasText(wfVersion) && !wfKeys.contains(wfKey))
        ri.add("workflow edge references missing definition: " + wfKey);
      if (hasText(wfCode)
          && hasText(wfVersion)
          && hasText(fromNode)
          && !nodeKeys.contains(wfKey + KEY_SEP_HASH + fromNode))
        ri.add("from_node_code references unknown node: " + fromNode);
      if (hasText(wfCode)
          && hasText(wfVersion)
          && hasText(toNode)
          && !nodeKeys.contains(wfKey + KEY_SEP_HASH + toNode))
        ri.add("to_node_code references unknown node: " + toNode);
      addIssues(ri, WF_EDGE_SHEET, rowNo, issues);
      if (ri.isEmpty()) valid.add(row);
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
            .filter(StringUtils::hasText)
            .collect(Collectors.toSet());
    Set<String> pipelineJobCodesInExcel =
        validPipelines.stream()
            .map(r -> normalize(r.get(COL_JOB_CODE)))
            .filter(StringUtils::hasText)
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

  // ── shared utilities ──────────────────────────────────────────────────────

  static String normalize(String value) {
    return com.example.batch.common.utils.ConsoleTextSanitizer.normalize(value);
  }

  static String normalizeEnum(String value) {
    String n = normalize(value);
    return n == null ? null : n.toUpperCase(java.util.Locale.ROOT);
  }

  static boolean hasText(String value) {
    return StringUtils.hasText(value);
  }

  private static void addIssues(
      List<String> rowIssues, String sheetName, int rowNo, List<WorkbookIssue> issues) {
    for (String msg : rowIssues) {
      issues.add(new WorkbookIssue(sheetName, rowNo, null, msg));
    }
  }
}
