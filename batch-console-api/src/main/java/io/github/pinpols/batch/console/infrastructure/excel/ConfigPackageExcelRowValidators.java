package io.github.pinpols.batch.console.infrastructure.excel;

import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.AUTH_TYPES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.CHANNEL_TYPES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_AUTH_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_CHANNEL_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_CHANNEL_NAME;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_CHANNEL_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_CONFIG_JSON;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_DEPENDS_ON_JOB_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_EDGE_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_EXECUTION_MODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_FROM_NODE_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_IMPL_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_JOB_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_JOB_NAME;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_JOB_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_NODE_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_NODE_NAME;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_NODE_PARAMS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_NODE_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_PARAM_SCHEMA;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_PIPELINE_NAME;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_PIPELINE_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_RECEIPT_POLICY;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_RETRY_POLICY;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_SCHEDULE_EXPR;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_SCHEDULE_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_SHARD_STRATEGY;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_STAGE_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_STEP_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_STEP_NAME;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_TO_NODE_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_VERSION;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_WORKFLOW_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_WORKFLOW_NAME;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_WORKFLOW_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_WORKFLOW_VERSION;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.EDGE_TYPES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.EXECUTION_MODES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.JOB_TYPES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.KEY_SEP_COLON;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.KEY_SEP_HASH;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.NODE_TYPES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.PIPELINE_TYPES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.RECEIPT_POLICIES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.RETRY_POLICIES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.SCHEDULE_TYPES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.SHARD_STRATEGIES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.STAGES_BY_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.STAGE_CODES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.WORKFLOW_TYPES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.hasText;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.normalize;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.normalizeEnum;
import static io.github.pinpols.batch.console.support.excel.SheetValidationHelpers.optionalEnum;
import static io.github.pinpols.batch.console.support.excel.SheetValidationHelpers.requireField;
import static io.github.pinpols.batch.console.support.excel.SheetValidationHelpers.requireIntField;
import static io.github.pinpols.batch.console.support.excel.SheetValidationHelpers.requiredEnum;
import static io.github.pinpols.batch.console.support.excel.SheetValidationHelpers.validateJsonField;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.job.mapper.StepRegistryQueryMapper;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 配置包 Excel 各 sheet 的行级校验器（从 {@link ConfigPackageExcelValidator} 拆出）。 只做单行格式/枚举/JSON 结构校验与
 * step_registry 白名单核对；跨行重复、跨 sheet 引用与 DAG 拓扑由 facade 与 {@link
 * WorkflowGraphTopologyValidator} 负责。
 */
final class ConfigPackageExcelRowValidators {

  private final StepRegistryQueryMapper stepRegistryQueryMapper;
  private final FileTemplateConfigMapper fileTemplateConfigMapper;
  private final ObjectMapper objectMapper = JsonUtils.newDefaultMapper();

  ConfigPackageExcelRowValidators(
      StepRegistryQueryMapper stepRegistryQueryMapper,
      FileTemplateConfigMapper fileTemplateConfigMapper) {
    this.stepRegistryQueryMapper = stepRegistryQueryMapper;
    this.fileTemplateConfigMapper = fileTemplateConfigMapper;
  }

  static void validateJobRow(
      String tenantId, Map<String, String> row, Set<String> seen, List<String> ri) {
    String jobCode = normalize(row.get(COL_JOB_CODE));
    requireField(ri, jobCode, COL_JOB_CODE);
    requireField(ri, normalize(row.get(COL_JOB_NAME)), COL_JOB_NAME);
    requiredEnum(normalizeEnum(row.get(COL_JOB_TYPE)), COL_JOB_TYPE, JOB_TYPES, ri);
    requiredEnum(normalizeEnum(row.get(COL_SCHEDULE_TYPE)), COL_SCHEDULE_TYPE, SCHEDULE_TYPES, ri);
    optionalEnum(normalizeEnum(row.get(COL_RETRY_POLICY)), COL_RETRY_POLICY, RETRY_POLICIES, ri);
    optionalEnum(
        normalizeEnum(row.get(COL_SHARD_STRATEGY)), COL_SHARD_STRATEGY, SHARD_STRATEGIES, ri);
    optionalEnum(
        normalizeEnum(row.get(COL_EXECUTION_MODE)), COL_EXECUTION_MODE, EXECUTION_MODES, ri);
    validateOptionalJobCodeRef(normalize(row.get(COL_DEPENDS_ON_JOB_CODE)), ri);
    validateJsonField(row.get(COL_PARAM_SCHEMA), COL_PARAM_SCHEMA, false, ri);
    validateCronSchedule(row, ri);
    if (hasText(jobCode) && !seen.add(tenantId + KEY_SEP_HASH + jobCode)) {
      ri.add("duplicate job_code in excel: " + jobCode);
    }
  }

  /**
   * #2 schedule_type=CRON 时 schedule_expr 必填且须为 Quartz 6/7 字段(对齐 {@link CronExpressionFormatRule}),
   * 把 Linux 5 字段等脏 cron 挡在预览期,而非运行期 trigger 才 fail。
   */
  static void validateCronSchedule(Map<String, String> row, List<String> ri) {
    if (!"CRON".equals(normalizeEnum(row.get(COL_SCHEDULE_TYPE)))) {
      return;
    }
    String expr = normalize(row.get(COL_SCHEDULE_EXPR));
    if (!hasText(expr)) {
      ri.add("schedule_expr is required when schedule_type=CRON");
      return;
    }
    int fields = expr.trim().split("\\s+").length;
    if (fields != 6 && fields != 7) {
      ri.add("schedule_expr must be a Quartz 6 or 7-field cron (sec min hour dom mon dow [year]);"
          + " found "
          + fields
          + " fields: '"
          + expr
          + "'");
    }
  }

  static void validateChannelRow(
      String tenantId, Map<String, String> row, Set<String> seen, List<String> ri) {
    String code = normalize(row.get(COL_CHANNEL_CODE));
    requireField(ri, code, COL_CHANNEL_CODE);
    requireField(ri, normalize(row.get(COL_CHANNEL_NAME)), COL_CHANNEL_NAME);
    requiredEnum(normalizeEnum(row.get(COL_CHANNEL_TYPE)), COL_CHANNEL_TYPE, CHANNEL_TYPES, ri);
    requiredEnum(normalizeEnum(row.get(COL_AUTH_TYPE)), COL_AUTH_TYPE, AUTH_TYPES, ri);
    requiredEnum(
        normalizeEnum(row.get(COL_RECEIPT_POLICY)), COL_RECEIPT_POLICY, RECEIPT_POLICIES, ri);
    validateJsonField(row.get(COL_CONFIG_JSON), COL_CONFIG_JSON, true, ri);
    if (hasText(code) && !seen.add(tenantId + KEY_SEP_HASH + code)) {
      ri.add("duplicate channel_code in excel: " + code);
    }
  }

  private static final Pattern SELECT_STAR = Pattern.compile("(?i)select\\s+\\*");
  private static final Pattern FORBIDDEN_SQL =
      Pattern.compile("(?i)\\b(UPDATE|DELETE|INSERT|DROP|ALTER|TRUNCATE|GRANT|MERGE|EXEC)\\b");

  /** #4a file_format_type=DELIMITED 时 delimiter 必填(否则 CSV 解析无分隔符,运行期才失败)。 */
  static void validateFormatConditionals(Map<String, String> row, List<String> ri) {
    if ("DELIMITED".equals(normalizeEnum(row.get("file_format_type")))
        && !hasText(normalize(row.get("delimiter")))) {
      ri.add("delimiter is required when file_format_type=DELIMITED");
    }
  }

  /**
   * #3 default_query_sql 预览期轻治理:只允许 SELECT/WITH,禁 SELECT * 与 DML/DDL(worker 期仍做 JSqlParser 全治理)。
   */
  static void validateExportSql(Map<String, String> row, List<String> ri) {
    String sql = normalize(row.get("default_query_sql"));
    if (!hasText(sql)) {
      return;
    }
    String upper = sql.trim().toUpperCase(Locale.ROOT);
    if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
      ri.add("default_query_sql must be a single SELECT/WITH query");
    }
    if (SELECT_STAR.matcher(sql).find()) {
      ri.add("default_query_sql must not use SELECT * (list columns explicitly)");
    }
    if (FORBIDDEN_SQL.matcher(sql).find()) {
      ri.add("default_query_sql must not contain DML/DDL keywords (UPDATE/DELETE/INSERT/...)");
    }
  }

  /**
   * #1 JSON 字段深层结构校验(语法已由 parser 校验):field_mappings 每项须有非空 name; query_param_schema 的
   * jdbcMappedImport 须有 table/tenantColumn、jdbcMappedExport 须有 batchTable/detailTable。 不强制
   * columnMappings(可从 field_mappings 推断)。
   */
  void validateTemplateJsonStructure(Map<String, String> row, List<String> ri) {
    JsonNode fieldMappings = tryReadJson(normalize(row.get("field_mappings")));
    if (fieldMappings != null && fieldMappings.isArray()) {
      for (JsonNode entry : fieldMappings) {
        if (!hasText(firstText(entry, "name"))) {
          ri.add("field_mappings entries must each have a non-blank 'name'");
          break;
        }
      }
    }
    JsonNode qps = tryReadJson(normalize(row.get("query_param_schema")));
    if (qps == null) {
      return;
    }
    JsonNode imp = qps.get("jdbcMappedImport");
    if (imp != null && imp.isObject()) {
      requireJsonText(imp, "query_param_schema.jdbcMappedImport.table", ri, "table");
      requireJsonText(imp, "query_param_schema.jdbcMappedImport.tenantColumn", ri, "tenantColumn");
    }
    JsonNode exp = qps.get("jdbcMappedExport");
    if (exp != null && exp.isObject()) {
      requireJsonText(exp, "query_param_schema.jdbcMappedExport.batchTable", ri, "batchTable");
      requireJsonText(exp, "query_param_schema.jdbcMappedExport.detailTable", ri, "detailTable");
    }
  }

  private static void requireJsonText(JsonNode node, String label, List<String> ri, String field) {
    if (!hasText(firstText(node, field))) {
      ri.add(label + " is required");
    }
  }

  private JsonNode tryReadJson(String text) {
    if (!hasText(text)) {
      return null;
    }
    try {
      return objectMapper.readTree(text);
    } catch (JsonProcessingException e) {
      return null; // 语法错误已由 parser 的 JSON 合法性校验单独报出
    }
  }

  static void validatePipelineRow(
      String tenantId, Map<String, String> row, Set<String> seen, List<String> ri) {
    String jobCode = normalize(row.get(COL_JOB_CODE));
    String version = normalize(row.get(COL_VERSION));
    requireField(ri, jobCode, "job_code");
    requireField(ri, normalize(row.get(COL_PIPELINE_NAME)), COL_PIPELINE_NAME);
    requiredEnum(normalizeEnum(row.get(COL_PIPELINE_TYPE)), COL_PIPELINE_TYPE, PIPELINE_TYPES, ri);
    requireIntField(version, COL_VERSION, ri);
    if (hasText(jobCode)
        && hasText(version)
        && !seen.add(tenantId + KEY_SEP_HASH + jobCode + KEY_SEP_COLON + version)) {
      ri.add("duplicate pipeline key (job_code + version): " + jobCode + KEY_SEP_COLON + version);
    }
  }

  static Map<String, String> buildPipelineKeyToType(List<Map<String, String>> validPipelineRows) {
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

  void validateStepRow(
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
    requireField(ri, stepCode, COL_STEP_CODE);
    requireField(ri, normalize(row.get(COL_STEP_NAME)), COL_STEP_NAME);

    String pipelineKey = jobCode + KEY_SEP_COLON + version;
    validateStageCode(row, pipelineKey, pipelineKeyToType, ri);
    validateRetryPolicy(row, ri);
    validatePipelineLink(jobCode, version, stepCode, pipelineKey, pipelineKeys, seen, ri);
    validateImplCode(row, implCode, pipelineKey, pipelineKeyToType, registryByModule, ri);
  }

  private static void validateStageCode(
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
   * impl_code 白名单 + 模块匹配:
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
          ri.add("impl_code prefix '"
              + prefix
              + "' 与 pipeline_type '"
              + pipelineType
              + "' 不匹配，请改选同模块的 Step");
        }
        normalizedImpl = implCode.substring(colonIdx + 1).trim();
        row.put(COL_IMPL_CODE, normalizedImpl);
      }
    }
    Set<String> registered = registryByModule.computeIfAbsent(
        pipelineType, m -> new HashSet<>(stepRegistryQueryMapper.selectImplCodesByModule(m)));
    if (EmptyChecks.isNotEmpty(registered) && !registered.contains(normalizedImpl)) {
      ri.add("impl_code '"
          + normalizedImpl
          + "' not registered in module "
          + pipelineType
          + "（检查 Spring bean name 是否存在或 worker 是否启动过以刷新 step_registry）");
    }
  }

  static void validateWfDefRow(
      String tenantId, Map<String, String> row, Set<String> seen, List<String> ri) {
    String wfCode = normalize(row.get(COL_WORKFLOW_CODE));
    String version = normalize(row.get(COL_VERSION));
    requireField(ri, wfCode, COL_WORKFLOW_CODE);
    requireField(ri, normalize(row.get(COL_WORKFLOW_NAME)), COL_WORKFLOW_NAME);
    requiredEnum(normalizeEnum(row.get(COL_WORKFLOW_TYPE)), COL_WORKFLOW_TYPE, WORKFLOW_TYPES, ri);
    requireIntField(version, "version", ri);
    if (hasText(wfCode)
        && hasText(version)
        && !seen.add(tenantId + KEY_SEP_HASH + wfCode + KEY_SEP_COLON + version)) {
      ri.add("duplicate workflow definition: " + wfCode + KEY_SEP_COLON + version);
    }
  }

  static void validateWfNodeRow(
      Map<String, String> row, Set<String> wfKeys, Set<String> seen, List<String> ri) {
    String wfCode = normalize(row.get(COL_WORKFLOW_CODE));
    String wfVersion = normalize(row.get(COL_WORKFLOW_VERSION));
    String nodeCode = normalize(row.get(COL_NODE_CODE));
    requireField(ri, wfCode, "workflow_code");
    requireField(ri, wfVersion, COL_WORKFLOW_VERSION);
    requireField(ri, nodeCode, COL_NODE_CODE);
    requireField(ri, normalize(row.get(COL_NODE_NAME)), COL_NODE_NAME);
    requiredEnum(normalizeEnum(row.get(COL_NODE_TYPE)), COL_NODE_TYPE, NODE_TYPES, ri);
    optionalEnum(normalizeEnum(row.get(COL_RETRY_POLICY)), "retry_policy", RETRY_POLICIES, ri);
    validateJsonField(row.get(COL_NODE_PARAMS), COL_NODE_PARAMS, false, ri);
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

  static void validateWfEdgeRow(
      Map<String, String> row, Set<String> wfKeys, Set<String> nodeKeys, List<String> ri) {
    String wfCode = normalize(row.get(COL_WORKFLOW_CODE));
    String wfVersion = normalize(row.get(COL_WORKFLOW_VERSION));
    String fromNode = normalize(row.get(COL_FROM_NODE_CODE));
    String toNode = normalize(row.get(COL_TO_NODE_CODE));
    requireField(ri, wfCode, "workflow_code");
    requireField(ri, wfVersion, "workflow_version");
    requireField(ri, fromNode, COL_FROM_NODE_CODE);
    requireField(ri, toNode, COL_TO_NODE_CODE);
    requiredEnum(normalizeEnum(row.get(COL_EDGE_TYPE)), COL_EDGE_TYPE, EDGE_TYPES, ri);
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

  static Set<String> buildFileTemplateKeys(List<Map<String, String>> rows) {
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

  TemplateRef extractTemplateRef(String json) {
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

  boolean fileTemplateExists(String tenantId, TemplateRef ref) {
    if (ref.version() != null) {
      Map<String, Object> found =
          fileTemplateConfigMapper.selectByUniqueKey(tenantId, ref.templateCode(), ref.version());
      return EmptyChecks.isNotEmpty(found);
    }
    Map<String, Object> found =
        fileTemplateConfigMapper.selectSecurityFlagsByTemplateCode(tenantId, ref.templateCode());
    return EmptyChecks.isNotEmpty(found);
  }

  static String templateKey(String templateCode, Integer version) {
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

  static void validateOptionalJobCodeRef(String value, List<String> ri) {
    if (!hasText(value)) {
      return;
    }
    if (!value.matches("^[a-zA-Z][a-zA-Z0-9_-]{0,63}$")) {
      ri.add("depends_on_job_code must start with a letter and contain only letters, digits,"
          + " underscore or hyphen");
    }
  }

  record TemplateRef(String templateCode, Integer version) {
    private static TemplateRef empty() {
      return new TemplateRef(null, null);
    }

    boolean hasTemplateCode() {
      return hasText(templateCode);
    }
  }
}
