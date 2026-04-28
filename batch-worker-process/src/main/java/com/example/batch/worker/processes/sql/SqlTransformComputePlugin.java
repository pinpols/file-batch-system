package com.example.batch.worker.processes.sql;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.jdbc.JdbcMappedSqlValidator;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.processes.domain.ProcessJobContext;
import com.example.batch.worker.processes.domain.ProcessPayload;
import com.example.batch.worker.processes.domain.ProcessStage;
import com.example.batch.worker.processes.domain.ProcessStageResult;
import com.example.batch.worker.processes.stage.ProcessComputePlugin;
import com.example.batch.worker.processes.stage.ProcessRuntimeKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内置 PROCESS 加工插件:基于 step_params 的"配置驱动 SQL transform",落 WAP+bookends 五段式。
 *
 * <ul>
 *   <li>PREPARE:解析 spec、jsqlparser AST 校验、identifier 校验、目标表存在性校验
 *   <li>COMPUTE:执行源 SELECT,把每行序列化为 JSONB 写入 {@code batch.process_staging}
 *   <li>VALIDATE:跑默认 row_count_positive + 用户配置的 validations
 *   <li>COMMIT:用 {@code jsonb_populate_record} 把 staging 反序列化为目标表行,原子写入(单 SQL ON CONFLICT)
 *   <li>FEEDBACK:清理 staging、推进水位
 * </ul>
 */
@Slf4j
@Component
public class SqlTransformComputePlugin implements ProcessComputePlugin {

  public static final String PLUGIN_ID = "sqlTransformCompute";

  // 命名参数:`:name`,但排除 PostgreSQL cast 语法 `::type`(双冒号前缀)。
  private static final Pattern NAMED_PARAMETER = Pattern.compile("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)");

  /** 业务参数走 payload.metadata,展开为 :metadata_&lt;key&gt;。前缀公开,避免与内置参数冲突。 */
  private static final String METADATA_PARAM_PREFIX = "metadata_";

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final SqlTransformComputeSecurityProperties security;
  private final SqlTransformComputeSqlValidator sqlValidator;

  public SqlTransformComputePlugin(
      @Qualifier("processBusinessDataSource") DataSource processBusinessDataSource,
      ObjectMapper objectMapper,
      SqlTransformComputeSecurityProperties security) {
    JdbcTemplate template = new JdbcTemplate(processBusinessDataSource);
    template.setQueryTimeout(
        Math.max(1, security == null ? 60 : security.getQueryTimeoutSeconds()));
    this.jdbc = new NamedParameterJdbcTemplate(template);
    this.objectMapper = objectMapper;
    this.security = security;
    this.sqlValidator = new SqlTransformComputeSqlValidator(security);
  }

  @Override
  public String implCode() {
    return PLUGIN_ID;
  }

  // ─── PREPARE ─────────────────────────────────────────────────────────────────

  @Override
  public void prepare(ProcessJobContext context) {
    Map<String, Object> stepParams = readComputeStepParams(context);
    SqlTransformComputeSpec spec = SqlTransformComputeSpec.parse(stepParams, objectMapper);
    JdbcMappedSqlValidator.requireInAllowlist(
        spec.targetSchema(), security == null ? null : security.getAllowedSchemas());
    String validatedSourceSql = sqlValidator.validateSelect(spec.sourceSql());
    Map<String, Object> sqlParams = buildSqlParams(context, spec);
    validateNamedParameters(validatedSourceSql, sqlParams);
    requireTargetTableExists(spec);
    context.getAttributes().put(ProcessRuntimeKeys.PROCESS_PARSED_SPEC, spec);
    log.info(
        "sqlTransformCompute prepare passed: tenantId={}, jobCode={}, target={}.{}, batchKey={}",
        context.getTenantId(),
        context.getJobCode(),
        spec.targetSchema(),
        spec.targetTable(),
        context.getBatchKey());
  }

  // ─── COMPUTE ─────────────────────────────────────────────────────────────────

  @Override
  public ProcessStageResult compute(ProcessJobContext context) {
    SqlTransformComputeSpec spec = parsedSpec(context);
    String batchKey = requireBatchKey(context);
    // buildSqlParams 已经注入 batchKey / targetSchema / targetTable,这里不再重复 put。
    Map<String, Object> params = buildSqlParams(context, spec);

    String stageSql = buildStagingInsertSql(spec);
    int stagedRows = jdbc.update(stageSql, params);
    context.getAttributes().put(ProcessRuntimeKeys.PROCESS_STAGED_COUNT, stagedRows);
    context.getAttributes().put("processedCount", stagedRows);

    if (Texts.hasText(spec.watermarkColumn())) {
      Object highWaterMarkOut = queryMaxWatermark(spec, batchKey, context.getTenantId());
      if (highWaterMarkOut != null) {
        context
            .getAttributes()
            .put(PipelineRuntimeKeys.HIGH_WATER_MARK_OUT, String.valueOf(highWaterMarkOut));
      }
    }
    log.info(
        "sqlTransformCompute staged: tenantId={}, batchKey={}, target={}.{}, stagedRows={}",
        context.getTenantId(),
        batchKey,
        spec.targetSchema(),
        spec.targetTable(),
        stagedRows);
    return ProcessStageResult.success(ProcessStage.COMPUTE);
  }

  // ─── VALIDATE ────────────────────────────────────────────────────────────────

  @Override
  public ProcessStageResult validate(ProcessJobContext context) {
    SqlTransformComputeSpec spec = parsedSpec(context);
    String batchKey = requireBatchKey(context);
    int stagedRows =
        toIntegerOrZero(context.getAttributes().get(ProcessRuntimeKeys.PROCESS_STAGED_COUNT));
    if (stagedRows == 0) {
      if (spec.emptyResultPolicy() == SqlTransformComputeSpec.EmptyResultPolicy.SUCCESS) {
        return ProcessStageResult.success(ProcessStage.VALIDATE);
      }
      return ProcessStageResult.failure(
          ProcessStage.VALIDATE, "PROCESS_STAGED_EMPTY", "no rows staged for batchKey=" + batchKey);
    }
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("batchKey", batchKey);
    params.put("tenantId", context.getTenantId());
    params.put("targetSchema", spec.targetSchema());
    params.put("targetTable", spec.targetTable());
    for (SqlTransformComputeSpec.ValidationRule rule : spec.validations()) {
      String checkSql = sqlValidator.validateUserCheckSelect(rule.checkSql());
      validateNamedParameters(checkSql, params);
      Map<String, Object> row;
      try {
        row = jdbc.queryForMap(checkSql, params);
      } catch (EmptyResultDataAccessException ex) {
        return ProcessStageResult.failure(
            ProcessStage.VALIDATE,
            "PROCESS_VALIDATION_NO_ROW",
            "validation '" + rule.name() + "' returned no row");
      }
      Object pass = row.get("pass");
      if (!(pass instanceof Boolean booleanPass) || !booleanPass) {
        Object message = row.get("message");
        return ProcessStageResult.failure(
            ProcessStage.VALIDATE,
            "PROCESS_VALIDATION_FAILED",
            "validation '"
                + rule.name()
                + "' failed: "
                + (message == null ? "(no message)" : message));
      }
    }
    return ProcessStageResult.success(ProcessStage.VALIDATE);
  }

  // ─── COMMIT ──────────────────────────────────────────────────────────────────

  @Override
  @Transactional(transactionManager = "processBusinessTransactionManager")
  public ProcessStageResult commit(ProcessJobContext context) {
    SqlTransformComputeSpec spec = parsedSpec(context);
    String batchKey = requireBatchKey(context);
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("batchKey", batchKey);
    params.put("tenantId", context.getTenantId());
    params.put("targetSchema", spec.targetSchema());
    params.put("targetTable", spec.targetTable());
    String publishSql = buildPublishSql(spec);
    int publishedRows = jdbc.update(publishSql, params);
    int cleaned = cleanupCommittedStaging(params);
    context.getAttributes().put(ProcessRuntimeKeys.PROCESS_PUBLISHED_COUNT, publishedRows);
    log.info(
        "sqlTransformCompute published: tenantId={}, batchKey={}, target={}.{}, publishedRows={},"
            + " cleanedRows={}",
        context.getTenantId(),
        batchKey,
        spec.targetSchema(),
        spec.targetTable(),
        publishedRows,
        cleaned);
    return ProcessStageResult.success(ProcessStage.COMMIT);
  }

  // ─── FEEDBACK ────────────────────────────────────────────────────────────────

  @Override
  public ProcessStageResult feedback(ProcessJobContext context) {
    String batchKey = requireBatchKey(context);
    SqlTransformComputeSpec spec = parsedSpec(context);
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("batchKey", batchKey);
    params.put("tenantId", context.getTenantId());
    params.put("targetSchema", spec.targetSchema());
    params.put("targetTable", spec.targetTable());
    int cleaned = cleanupCommittedStaging(params);
    log.info(
        "sqlTransformCompute feedback cleaned staging: tenantId={}, batchKey={}, deletedRows={}",
        context.getTenantId(),
        batchKey,
        cleaned);
    return ProcessStageResult.success(ProcessStage.FEEDBACK);
  }

  // ─── helpers ─────────────────────────────────────────────────────────────────

  /**
   * 从 context 里读出 COMPUTE step 的 step_params(由 DefaultProcessStageExecutor 在 PREPARE 之前 stash);
   * fallback 到 PIPELINE_CURRENT_STEP_PARAMS(兼容老代码路径)。
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> readComputeStepParams(ProcessJobContext context) {
    Object value = context.getAttributes().get(ProcessRuntimeKeys.PROCESS_COMPUTE_STEP_PARAMS);
    if (value == null) {
      value = context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_CURRENT_STEP_PARAMS);
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      map.forEach((key, item) -> out.put(String.valueOf(key), item));
      return out;
    }
    if (value instanceof String text && Texts.hasText(text)) {
      try {
        return objectMapper.readValue(text, Map.class);
      } catch (Exception e) {
        throw new BizException(
            ResultCode.INVALID_ARGUMENT,
            "sqlTransformCompute step_params is not a JSON object: " + e.getMessage(),
            e);
      }
    }
    return Map.of();
  }

  private SqlTransformComputeSpec parsedSpec(ProcessJobContext context) {
    Object value = context.getAttributes().get(ProcessRuntimeKeys.PROCESS_PARSED_SPEC);
    if (value instanceof SqlTransformComputeSpec spec) {
      return spec;
    }
    throw new BizException(
        ResultCode.SYSTEM_ERROR, "sqlTransformCompute spec missing in context (prepare not run?)");
  }

  private String requireBatchKey(ProcessJobContext context) {
    String batchKey = context.getBatchKey();
    if (!Texts.hasText(batchKey)) {
      throw new BizException(ResultCode.SYSTEM_ERROR, "process batchKey not set in context");
    }
    return batchKey;
  }

  private void requireTargetTableExists(SqlTransformComputeSpec spec) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("schema", spec.targetSchema());
    params.put("table", spec.targetTable());
    Boolean exists =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = :schema"
                + " AND table_name = :table)",
            params,
            Boolean.class);
    if (exists == null || !exists) {
      throw new BizException(
          ResultCode.INVALID_ARGUMENT,
          "sqlTransformCompute target table not found: "
              + spec.targetSchema()
              + "."
              + spec.targetTable());
    }
  }

  /**
   * 构造 SQL 命名参数。三层来源,优先级 = 1 内置 > 2 metadata > 3 spec.params。
   *
   * <ol>
   *   <li>内置(平台保留): tenantId / jobCode / workerId / traceId / stepCode / highWaterMarkIn / batchKey
   *       / targetSchema / targetTable / bizDate。spec.params 不可覆盖(由 {@link
   *       SqlTransformComputeSpec#validateIdentifiers()} 拦截);本方法也以"先 put 内置,后 putAll
   *       spec.params"作为防御性双重保护。
   *   <li>业务: 仅 {@code payload.metadata.<key>} 展开为 {@code :metadata_<key>},不再自动散开整个 attributes
   *       map(避免 attribute 名碰巧与用户 SQL 命名参数同名导致绑定错值)。
   *   <li>spec: {@code sqlTransformCompute.params} 显式定义的额外参数。
   * </ol>
   */
  private Map<String, Object> buildSqlParams(
      ProcessJobContext context, SqlTransformComputeSpec spec) {
    Map<String, Object> params = new LinkedHashMap<>();
    // 1. 内置参数
    params.put("tenantId", context.getTenantId());
    params.put("jobCode", context.getJobCode());
    params.put("workerId", context.getWorkerId());
    params.put("traceId", context.getAttributes().get(PipelineRuntimeKeys.TRACE_ID));
    params.put(
        "stepCode", context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_CURRENT_STEP_CODE));
    params.put(
        "highWaterMarkIn", context.getAttributes().get(PipelineRuntimeKeys.HIGH_WATER_MARK_IN));
    params.put("batchKey", context.getBatchKey());
    params.put("targetSchema", spec.targetSchema());
    params.put("targetTable", spec.targetTable());
    // 2. 业务级 bizDate + payload.metadata 展开
    if (context.getAttributes().get("processPayload") instanceof ProcessPayload typedPayload) {
      if (Texts.hasText(typedPayload.bizDate())) {
        params.put("bizDate", typedPayload.bizDate());
      }
      if (typedPayload.metadata() != null) {
        typedPayload.metadata().forEach((key, value) -> putMetadataParam(params, key, value));
      }
    } else {
      // 兜底:某些路径(如插件单测)可能还没把 ProcessPayload 注入到 attributes,
      // 直接从顶层 attributes 读 bizDate 字符串保持兼容(只读 bizDate 一项,不再全量散开)。
      Object bizDate = context.getAttributes().get("bizDate");
      if (bizDate != null) {
        params.put("bizDate", bizDate);
      }
    }
    // 3. spec.params 用户自定义(不可覆盖内置)
    params.putAll(spec.params());
    return params;
  }

  private void putMetadataParam(Map<String, Object> params, String key, Object value) {
    if (key == null || key.isBlank()) {
      return;
    }
    if (value != null
        && !(value instanceof String)
        && !(value instanceof Number)
        && !(value instanceof Boolean)
        && !(value instanceof java.time.temporal.TemporalAccessor)) {
      // 复杂值(嵌套 Map / List)不直接绑定为 SQL 参数,业务需要先在 metadata 里展平。
      return;
    }
    params.put(METADATA_PARAM_PREFIX + key, value);
  }

  private void validateNamedParameters(String sourceSql, Map<String, Object> params) {
    Matcher matcher = NAMED_PARAMETER.matcher(sourceSql);
    while (matcher.find()) {
      String name = matcher.group(1);
      if (!params.containsKey(name)) {
        throw new BizException(
            ResultCode.INVALID_ARGUMENT,
            "sqlTransformCompute SQL references unknown named parameter :" + name);
      }
    }
  }

  /** COMPUTE 写 staging 的 SQL:把源 SELECT 包成 SUBSELECT,逐行 jsonb_build_object 序列化到 staging payload。 */
  static String buildStagingInsertSql(SqlTransformComputeSpec spec) {
    String jsonbBuild =
        spec.columns().stream()
            .map(
                column ->
                    "'"
                        + column.target()
                        + "', base."
                        + JdbcMappedSqlValidator.quotePg(column.source()))
            .collect(java.util.stream.Collectors.joining(", "));
    return """
    INSERT INTO batch.process_staging (batch_key, tenant_id, target_schema, target_table, payload)
    SELECT :batchKey, :tenantId, :targetSchema, :targetTable, jsonb_build_object(%s)
    FROM (
    %s
    ) base
    """
        .formatted(jsonbBuild, spec.sourceSql());
  }

  /** COMMIT 用 jsonb_populate_record 反序列化到目标表行类型,单 SQL ON CONFLICT 原子上线。 */
  static String buildPublishSql(SqlTransformComputeSpec spec) {
    String target =
        JdbcMappedSqlValidator.quotePg(spec.targetSchema())
            + "."
            + JdbcMappedSqlValidator.quotePg(spec.targetTable());
    String columnList =
        spec.columns().stream()
            .map(SqlTransformComputeSpec.ColumnMapping::target)
            .map(JdbcMappedSqlValidator::quotePg)
            .collect(java.util.stream.Collectors.joining(", "));
    String selectColumns =
        spec.columns().stream()
            .map(column -> "(rec)." + JdbcMappedSqlValidator.quotePg(column.target()))
            .collect(java.util.stream.Collectors.joining(", "));
    String sql =
        """
        INSERT INTO %s (%s)
        SELECT %s
        FROM (
            SELECT jsonb_populate_record(NULL::%s, payload) AS rec
            FROM batch.process_staging
            WHERE batch_key = :batchKey
              AND tenant_id = :tenantId
              AND target_schema = :targetSchema
              AND target_table = :targetTable
        ) staged
        """
            .formatted(target, columnList, selectColumns, target);
    if (spec.writeMode() == SqlTransformComputeSpec.WriteMode.INSERT) {
      return sql;
    }
    String conflictColumns =
        spec.conflictColumns().stream()
            .map(JdbcMappedSqlValidator::quotePg)
            .collect(java.util.stream.Collectors.joining(", "));
    if (spec.writeMode() == SqlTransformComputeSpec.WriteMode.INSERT_IGNORE) {
      return sql + " ON CONFLICT (" + conflictColumns + ") DO NOTHING";
    }
    String update =
        spec.columns().stream()
            .map(SqlTransformComputeSpec.ColumnMapping::target)
            .filter(column -> !spec.conflictColumns().contains(column))
            .map(
                column -> {
                  String quoted = JdbcMappedSqlValidator.quotePg(column);
                  return quoted + " = EXCLUDED." + quoted;
                })
            .collect(java.util.stream.Collectors.joining(", "));
    if (!Texts.hasText(update)) {
      return sql + " ON CONFLICT (" + conflictColumns + ") DO NOTHING";
    }
    return sql + " ON CONFLICT (" + conflictColumns + ") DO UPDATE SET " + update;
  }

  private Object queryMaxWatermark(SqlTransformComputeSpec spec, String batchKey, String tenantId) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("batchKey", batchKey);
    params.put("tenantId", tenantId);
    params.put("targetSchema", spec.targetSchema());
    params.put("targetTable", spec.targetTable());
    params.put("watermarkColumn", spec.watermarkColumn());
    String sql =
        """
        SELECT max((payload ->> :watermarkColumn)::numeric)
        FROM batch.process_staging
        WHERE batch_key = :batchKey
          AND tenant_id = :tenantId
          AND target_schema = :targetSchema
          AND target_table = :targetTable
        """;
    return jdbc.queryForObject(sql, params, Object.class);
  }

  private int cleanupCommittedStaging(Map<String, Object> params) {
    return jdbc.update(
        """
        DELETE FROM batch.process_staging
        WHERE batch_key = :batchKey
          AND tenant_id = :tenantId
          AND target_schema = :targetSchema
          AND target_table = :targetTable
        """,
        params);
  }

  private static int toIntegerOrZero(Object value) {
    if (value instanceof Number n) {
      return n.intValue();
    }
    if (value instanceof String s) {
      try {
        return Integer.parseInt(s);
      } catch (NumberFormatException ignored) {
        return 0;
      }
    }
    return 0;
  }

  /** 配合 SetUtils-style 的 list 兼容(reserved for future use)。 */
  @SuppressWarnings("unused")
  private static List<Object> emptyList() {
    return List.of();
  }
}
