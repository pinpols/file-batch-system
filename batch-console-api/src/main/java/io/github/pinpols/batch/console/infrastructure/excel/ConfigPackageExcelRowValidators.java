package io.github.pinpols.batch.console.infrastructure.excel;

import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.AUTH_TYPES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.CHANNEL_TYPES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_AUTH_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_CHANNEL_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_CHANNEL_NAME;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_CHANNEL_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_CONFIG_JSON;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_DEPENDS_ON_BIZ_DATE_ALIGNMENTS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_DEPENDS_ON_JOB_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_DEPENDS_ON_JOB_CODES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_EDGE_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_EXECUTION_MODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_FROM_NODE_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_JOB_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_JOB_NAME;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_JOB_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_NODE_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_NODE_NAME;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_NODE_PARAMS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_NODE_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_PARAM_SCHEMA;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_RECEIPT_POLICY;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_RETRY_POLICY;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_SCHEDULE_EXPR;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_SCHEDULE_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_SHARD_STRATEGY;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_TO_NODE_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_TRIGGER_MODE;
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
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.RECEIPT_POLICIES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.RETRY_POLICIES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.SCHEDULE_TYPES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.SHARD_STRATEGIES;
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
import io.github.pinpols.batch.common.sql.SelectSqlAstValidator;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.common.validation.ValidResourceCode;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.file.view.FileTemplateConfigQueryView;
import io.github.pinpols.batch.console.domain.file.view.FileTemplateSecurityFlagsView;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;

/**
 * 配置包 Excel 各 sheet 的行级校验器（从 {@link ConfigPackageExcelValidator} 拆出）。只做当前可导入 sheet 的行级格式、枚举与
 * JSON 结构校验；跨行重复、跨 sheet 引用与 DAG 拓扑由 facade 与 {@link WorkflowGraphTopologyValidator} 负责。
 */
final class ConfigPackageExcelRowValidators {

  private static final String INVALID_EXPORT_SQL_MESSAGE =
      "default_query_sql must be a single SELECT/WITH query";

  private final FileTemplateConfigMapper fileTemplateConfigMapper;
  private final ObjectMapper objectMapper = JsonUtils.newDefaultMapper();

  ConfigPackageExcelRowValidators(FileTemplateConfigMapper fileTemplateConfigMapper) {
    this.fileTemplateConfigMapper = fileTemplateConfigMapper;
  }

  static void validateJobRow(
      String tenantId, Map<String, String> row, Set<String> seen, List<String> ri) {
    String jobCode = normalize(row.get(COL_JOB_CODE));
    requireField(ri, jobCode, COL_JOB_CODE);
    if (hasText(jobCode) && !jobCode.matches(ValidResourceCode.REGEX)) {
      ri.add(
          "job_code must start with a letter and contain only letters, digits, underscore or hyphen, max 128");
    }
    requireField(ri, normalize(row.get(COL_JOB_NAME)), COL_JOB_NAME);
    requiredEnum(normalizeEnum(row.get(COL_JOB_TYPE)), COL_JOB_TYPE, JOB_TYPES, ri);
    requiredEnum(normalizeEnum(row.get(COL_SCHEDULE_TYPE)), COL_SCHEDULE_TYPE, SCHEDULE_TYPES, ri);
    optionalEnum(normalizeEnum(row.get(COL_RETRY_POLICY)), COL_RETRY_POLICY, RETRY_POLICIES, ri);
    optionalEnum(
        normalizeEnum(row.get(COL_SHARD_STRATEGY)), COL_SHARD_STRATEGY, SHARD_STRATEGIES, ri);
    optionalEnum(
        normalizeEnum(row.get(COL_EXECUTION_MODE)), COL_EXECUTION_MODE, EXECUTION_MODES, ri);
    validateOptionalJobCodeRef(normalize(row.get(COL_DEPENDS_ON_JOB_CODE)), ri);
    for (String upstream : splitDependencyCodes(row.get(COL_DEPENDS_ON_JOB_CODES))) {
      validateOptionalJobCodeRef(upstream, ri);
    }
    for (String alignment : splitDependencyCodes(row.get(COL_DEPENDS_ON_BIZ_DATE_ALIGNMENTS))) {
      optionalEnum(
          normalizeEnum(alignment),
          COL_DEPENDS_ON_BIZ_DATE_ALIGNMENTS,
          Set.of("SAME_DAY", "PREVIOUS_BIZ_DAY"),
          ri);
    }
    validateJsonField(row.get(COL_PARAM_SCHEMA), COL_PARAM_SCHEMA, false, ri);
    validateCronSchedule(row, ri);
    if (hasText(jobCode) && !seen.add(tenantId + KEY_SEP_HASH + jobCode)) {
      ri.add("duplicate job_code in excel: " + jobCode);
    }
  }

  private static List<String> splitDependencyCodes(String value) {
    if (!Texts.hasText(value)) {
      return List.of();
    }
    return Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(Texts::hasText)
        .distinct()
        .toList();
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
    String trimmedExpression = expr.trim();
    String[] expressionFields = trimmedExpression.split("\\s+");
    int fields = expressionFields.length;
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
    validateJsonField(row.get(COL_CONFIG_JSON), COL_CONFIG_JSON, false, ri);
    if (hasText(code) && !seen.add(tenantId + KEY_SEP_HASH + code)) {
      ri.add("duplicate channel_code in excel: " + code);
    }
  }

  /** #4a file_format_type=DELIMITED 时 delimiter 必填(否则 CSV 解析无分隔符,运行期才失败)。 */
  static void validateFormatConditionals(Map<String, String> row, List<String> ri) {
    boolean isDelimitedFormat = "DELIMITED".equals(normalizeEnum(row.get("file_format_type")));
    if (isDelimitedFormat && !hasText(normalize(row.get("delimiter")))) {
      ri.add("delimiter is required when file_format_type=DELIMITED");
    }
  }

  /**
   * #3 default_query_sql 预览期只允许单条 SELECT/WITH。按语法树识别语句类型，避免把注释和字符串常量中的
   * DML/DDL 单词误判为写操作；worker 期仍执行完整 SQL 安全治理。
   */
  static void validateExportSql(Map<String, String> row, List<String> ri) {
    String sql = normalize(row.get("default_query_sql"));
    if (!hasText(sql)) {
      return;
    }

    Statements parsedStatements;
    try {
      parsedStatements = CCJSqlParserUtil.parseStatements(sql);
    } catch (Exception e) {
      ri.add(INVALID_EXPORT_SQL_MESSAGE);
      return;
    }

    if (parsedStatements.size() != 1 || !(parsedStatements.get(0) instanceof Select select)) {
      ri.add(INVALID_EXPORT_SQL_MESSAGE);
      return;
    }
    if (containsNonReadOnlyQuery(select)) {
      ri.add(INVALID_EXPORT_SQL_MESSAGE);
      return;
    }
    if (SelectSqlAstValidator.containsSelectStar(select)) {
      ri.add("default_query_sql must not use SELECT * (list columns explicitly)");
    }
  }

  private static boolean containsNonReadOnlyQuery(Select select) {
    class ReadOnlyCteFinder extends TablesNamesFinder<Void> {
      private boolean found;

      boolean foundNonReadOnlyQuery() {
        return found;
      }

      @Override
      public <S> Void visit(PlainSelect plainSelect, S context) {
        boolean selectHasWriteOrLockClause = EmptyChecks.isNotEmpty(plainSelect.getIntoTables())
            || EmptyChecks.isNotNull(plainSelect.getIntoTempTable())
            || EmptyChecks.isNotNull(plainSelect.getMySqlSelectIntoClause())
            || EmptyChecks.isNotNull(plainSelect.getForUpdate())
            || EmptyChecks.isNotEmpty(plainSelect.getForUpdateTables());
        if (selectHasWriteOrLockClause) {
          found = true;
        }
        return super.visit(plainSelect, context);
      }

      @Override
      public <S> Void visit(WithItem<?> withItem, S context) {
        if (!(withItem.getParenthesedStatement() instanceof ParenthesedSelect)) {
          found = true;
        }
        return super.visit(withItem, context);
      }
    }
    ReadOnlyCteFinder finder = new ReadOnlyCteFinder();
    finder.getTables((Statement) select);
    return finder.foundNonReadOnlyQuery();
  }

  /**
   * #1 JSON 字段深层结构校验(语法已由 parser 校验):字段映射按文件格式校验; query_param_schema 的
   * jdbcMappedImport 须有 table/tenantColumn、jdbcMappedExport 须有 batchTable/detailTable。 不强制
   * columnMappings(可从 field_mappings 推断)。
   */
  void validateTemplateJsonStructure(Map<String, String> row, List<String> ri) {
    JsonNode fieldMappings = tryReadJson(normalize(row.get("field_mappings")));
    boolean fixedWidthImport = "IMPORT".equals(normalizeEnum(row.get("template_type")))
        && "FIXED_WIDTH".equals(normalizeEnum(row.get("file_format_type")));
    if (fixedWidthImport) {
      validateFixedWidthMappings(fieldMappings, ri);
    } else if (fieldMappings != null // empty-check: allow - Sonar S2259
        && fieldMappings.isArray()) { // empty-check: allow - Sonar S2259
      validateNamedMappings(fieldMappings, ri);
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

  private static void validateFixedWidthMappings(@Nullable JsonNode mappings, List<String> ri) {
    if (mappings == null // empty-check: allow - Sonar S2259
        || !mappings.isArray()
        || mappings.isEmpty()) { // empty-check: allow - JsonNode container semantics
      ri.add("FIXED_WIDTH imports require at least one field mapping");
      return;
    }
    for (JsonNode entry : mappings) {
      JsonNode target = entry.path("target");
      JsonNode start = entry.path("start");
      JsonNode length = entry.path("length");
      boolean mappingIsInvalid = !target.isTextual()
          || !hasText(target.textValue())
          || !start.isIntegralNumber()
          || !start.canConvertToInt()
          || start.intValue() < 0
          || !length.isIntegralNumber()
          || !length.canConvertToInt()
          || length.intValue() <= 0;
      if (mappingIsInvalid) {
        ri.add(
            "FIXED_WIDTH field mappings require non-blank target, non-negative start and positive"
                + " length");
        return;
      }
    }
  }

  private static void validateNamedMappings(JsonNode mappings, List<String> ri) {
    for (JsonNode entry : mappings) {
      if (!hasText(firstText(entry, "name"))) {
        ri.add("field_mappings entries must each have a non-blank 'name'");
        return;
      }
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

  static void validateWfDefRow(
      String tenantId, Map<String, String> row, Set<String> seen, List<String> ri) {
    String wfCode = normalize(row.get(COL_WORKFLOW_CODE));
    String version = normalize(row.get(COL_VERSION));
    requireField(ri, wfCode, COL_WORKFLOW_CODE);
    requireField(ri, normalize(row.get(COL_WORKFLOW_NAME)), COL_WORKFLOW_NAME);
    requiredEnum(normalizeEnum(row.get(COL_WORKFLOW_TYPE)), COL_WORKFLOW_TYPE, WORKFLOW_TYPES, ri);
    optionalEnum(normalizeEnum(row.get(COL_SCHEDULE_TYPE)), COL_SCHEDULE_TYPE, SCHEDULE_TYPES, ri);
    optionalEnum(
        normalizeEnum(row.get(COL_TRIGGER_MODE)),
        COL_TRIGGER_MODE,
        Set.of("SCHEDULED", "API", "MANUAL", "EVENT", "MIXED"),
        ri);
    requireIntField(version, "version", ri);
    boolean duplicateWorkflowDefinition = hasText(wfCode)
        && hasText(version)
        && !seen.add(tenantId + KEY_SEP_HASH + wfCode + KEY_SEP_COLON + version);
    if (duplicateWorkflowDefinition) {
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
    boolean referencesMissingWorkflowDefinition =
        hasText(wfCode) && hasText(wfVersion) && !wfKeys.contains(wfKey);
    if (referencesMissingWorkflowDefinition) {
      ri.add("workflow node references missing definition: " + wfKey);
    }
    boolean duplicateWorkflowNode = hasText(wfCode)
        && hasText(wfVersion)
        && hasText(nodeCode)
        && !seen.add(wfKey + KEY_SEP_HASH + nodeCode);
    if (duplicateWorkflowNode) {
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
    boolean hasWorkflowIdentity = hasText(wfCode) && hasText(wfVersion);
    boolean referencesMissingWorkflowDefinition = hasWorkflowIdentity && !wfKeys.contains(wfKey);
    if (referencesMissingWorkflowDefinition) {
      ri.add("workflow edge references missing definition: " + wfKey);
    }
    // 缺少 Workflow 身份的行已报告必填错误，不能再构造不完整的节点引用。
    if (hasWorkflowIdentity) {
      requireNodeRef(fromNode, wfKey, nodeKeys, "from_node_code", ri);
      requireNodeRef(toNode, wfKey, nodeKeys, "to_node_code", ri);
    }
  }

  private static void requireNodeRef(
      String node, String wfKey, Set<String> nodeKeys, String field, List<String> ri) {
    boolean referencesUnknownNode =
        hasText(node) && !nodeKeys.contains(wfKey + KEY_SEP_HASH + node);
    if (referencesUnknownNode) {
      ri.add(field + " references unknown node: " + node);
    }
  }

  static Set<String> buildFileTemplateKeys(List<Map<String, String>> rows) {
    Set<String> keys = new HashSet<>();
    for (Map<String, String> row : rows) {
      String templateCode = CodeNormalizer.toConfigFormOrNull(row.get("template_code"));
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
      String templateCode =
          CodeNormalizer.toConfigFormOrNull(firstText(root, "templateCode", "template_code"));
      Integer version = firstInt(root, "templateVersion", "template_version", "version");
      return new TemplateRef(templateCode, version);
    } catch (JsonProcessingException e) {
      return TemplateRef.empty();
    }
  }

  private static String firstText(JsonNode root, String... names) {
    for (String name : names) {
      JsonNode node = root.get(name);
      boolean nodeHasTextValue =
          EmptyChecks.isNotNull(node) && !node.isNull() && hasText(node.asText());
      if (nodeHasTextValue) {
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
      FileTemplateConfigQueryView found =
          fileTemplateConfigMapper.selectByUniqueKey(tenantId, ref.templateCode(), ref.version());
      return EmptyChecks.isNotNull(found);
    }
    FileTemplateSecurityFlagsView found =
        fileTemplateConfigMapper.selectSecurityFlagsByTemplateCode(tenantId, ref.templateCode());
    return found != null;
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
