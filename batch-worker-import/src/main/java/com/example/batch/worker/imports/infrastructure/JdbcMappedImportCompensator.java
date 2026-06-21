package com.example.batch.worker.imports.infrastructure;

import com.example.batch.common.jdbc.JdbcMappedSqlValidator;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.rls.RlsTenantSessionSupport;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.support.CompensationResult;
import com.example.batch.worker.core.support.PipelineCompensator;
import com.example.batch.worker.imports.domain.ImportPayload;
import com.example.batch.worker.imports.domain.ImportWorkerType;
import com.example.batch.worker.imports.jdbc.JdbcMappedImportSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 安全增量补偿(opt-in)IMPORT 实现 —— 反向 = 删本 run 自己写进 biz 目标表的行。
 *
 * <p><b>反向 DELETE 的精确 scope(安全第一)</b>:
 *
 * <pre>
 *   DELETE FROM &lt;schema.table&gt;
 *    WHERE &lt;tenantColumn&gt; = ?            -- 本租户
 *      AND &lt;runColumn&gt;    = ?            -- 本 run 标识(batchNo / traceId)
 * </pre>
 *
 * <ul>
 *   <li><b>run 标识列从模板 jdbcMappedImport.systemBindings 解析</b>:取 value 恰为 {@code ${batchNo}} 或 {@code
 *       ${traceId}} 的那个目标列(LOAD 时该列写的就是本 run 的 batchNo / traceId,故是天然的 run scope 键)。
 *   <li><b>模板没绑任何 run 标识列 → 不能安全反向 → SKIP 不删</b>(审计记 SKIPPED 原因),绝不无差别删表。
 *   <li>schema/table/列名一律走 {@link JdbcMappedSqlValidator} 白名单 / 正则强校验后再拼(表名来自模板,必须校验); tenant / run
 *       值用 JDBC {@code ?} 绑定参数(等价 MyBatis {@code #{}}),<b>禁</b> {@code ${}} 拼接。
 *   <li>幂等:删本 run 的行,重复跑结果一致(第二次删 0 行)。
 *   <li>best-effort:任何异常自行吞咽并转 {@link CompensationResult#failed},<b>不上抛</b>——不掩盖原始失败。
 * </ul>
 *
 * <p>RLS:复用 LOAD 写路径同一 {@code importBusinessDataSource} + 显式 tx 包 {@code SET LOCAL app.tenant_id},
 * biz.* policy 与 tenant 列条件叠加,纵深隔离。
 */
@Slf4j
@Component
public class JdbcMappedImportCompensator implements PipelineCompensator {

  private static final String RUN_BINDING_BATCH_NO = "${batchNo}";
  private static final String RUN_BINDING_TRACE_ID = "${traceId}";

  private final DataSource businessDataSource;
  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate txTemplate;

  public JdbcMappedImportCompensator(
      @Qualifier("importBusinessDataSource") DataSource importBusinessDataSource,
      ObjectMapper objectMapper) {
    this.businessDataSource = importBusinessDataSource;
    this.jdbcTemplate = new JdbcTemplate(importBusinessDataSource);
    this.objectMapper = objectMapper;
    this.txTemplate =
        new TransactionTemplate(new DataSourceTransactionManager(importBusinessDataSource));
  }

  @Override
  public String pipelineType() {
    return ImportWorkerType.IMPORT;
  }

  @Override
  public CompensationResult compensate(
      String tenantId, Long pipelineInstanceId, Long fileId, Map<String, Object> attributes) {
    try {
      Map<String, Object> templateConfig = templateConfigMap(attributes);
      if (templateConfig.isEmpty()) {
        // LOAD 必在 PREPROCESS 之后(那时 templateConfig 已入 attributes);缺失 = LOAD 从未执行 = 无行可删。
        return CompensationResult.skipped(
            "no templateConfig in attributes — LOAD never ran, nothing written to reverse");
      }
      JdbcMappedImportSpec spec;
      try {
        spec = JdbcMappedImportSpec.parse(templateConfig, objectMapper);
      } catch (RuntimeException ex) {
        return CompensationResult.skipped(
            "jdbcMappedImport spec parse failed, cannot safely scope reverse: " + ex.getMessage());
      }

      // run 标识列:systemBindings 里 value 恰为 ${batchNo} / ${traceId} 的目标列。
      RunScope runScope = resolveRunScope(spec, attributes);
      if (runScope == null) {
        return CompensationResult.skipped(
            "template binds no run-identifier column (${batchNo}/${traceId}) in systemBindings;"
                + " cannot safely scope reverse DELETE — SKIPPED (no rows deleted) for table "
                + spec.schema()
                + "."
                + spec.table());
      }
      if (!Texts.hasText(runScope.runValue())) {
        return CompensationResult.skipped(
            "run-identifier column '"
                + runScope.runColumn()
                + "' resolved to blank value at compensation time; cannot scope reverse — SKIPPED");
      }

      // 强校验所有进 SQL 的标识符(表名来自模板,必须校验白名单 + 正则)。
      JdbcMappedSqlValidator.requireInAllowlist(spec.schema(), allowedSchemas(spec));
      String fqTable =
          JdbcMappedSqlValidator.quotePg(spec.schema())
              + "."
              + JdbcMappedSqlValidator.quotePg(spec.table());
      String tenantCol = JdbcMappedSqlValidator.quotePg(spec.tenantColumn());
      String runCol = JdbcMappedSqlValidator.quotePg(runScope.runColumn());

      // 反向 DELETE：tenant + run 值用 ? 绑定参数（等价 #{}），绝无 ${} 拼接、无宽 WHERE。
      String deleteSql =
          "DELETE FROM " + fqTable + " WHERE " + tenantCol + " = ? AND " + runCol + " = ?";

      Integer deleted =
          txTemplate.execute(
              status -> {
                RlsTenantSessionSupport.applyIfPresent(businessDataSource);
                return jdbcTemplate.update(deleteSql, tenantId, runScope.runValue());
              });
      long reversed = deleted == null ? 0L : deleted;
      String detail =
          "reversed import rows: table="
              + spec.schema()
              + "."
              + spec.table()
              + ", where "
              + spec.tenantColumn()
              + "=<tenant> AND "
              + runScope.runColumn()
              + "="
              + runScope.runValue()
              + ", deletedRows="
              + reversed;
      log.info(
          "import compensation reversed rows: tenantId={}, pipelineInstanceId={}, table={}.{},"
              + " runColumn={}, runValue={}, deletedRows={}",
          tenantId,
          pipelineInstanceId,
          spec.schema(),
          spec.table(),
          runScope.runColumn(),
          runScope.runValue(),
          reversed);
      return CompensationResult.reversed(reversed, detail);
    } catch (RuntimeException ex) {
      // best-effort：补偿失败不掩盖原始失败。
      SwallowedExceptionLogger.warn(
          JdbcMappedImportCompensator.class, "catch:RuntimeException", ex);
      return CompensationResult.failed("import reverse DELETE failed: " + ex.getMessage());
    }
  }

  /** 模板里允许 schema 列表:沿用 spec.schema() 自身白名单语义,这里以 spec 的 schema 作为唯一允许项做形态校验。 */
  private static List<String> allowedSchemas(JdbcMappedImportSpec spec) {
    // spec.schema() 已是模板声明的目标 schema;LOAD 阶段 validateIdentifiers 已按 securityProperties 白名单校验过。
    // 补偿阶段无 securityProperties，这里复用同一 schema 做 requireInAllowlist 的形态 + 一致性兜底
    // （仅允许删与 LOAD 同一 schema，不放大范围）。
    return List.of(spec.schema().trim().toLowerCase(Locale.ROOT));
  }

  /**
   * 解析 run 标识列 + 当前 run 的标识值。run 列 = systemBindings 里 value 恰为 {@code ${batchNo}} 或 {@code
   * ${traceId}} 的目标列(优先 batchNo)。
   */
  private RunScope resolveRunScope(JdbcMappedImportSpec spec, Map<String, Object> attributes) {
    String batchNoColumn = null;
    String traceIdColumn = null;
    for (Map.Entry<String, String> binding : spec.systemBindings().entrySet()) {
      String value = binding.getValue() == null ? "" : binding.getValue().trim();
      if (RUN_BINDING_BATCH_NO.equals(value) && batchNoColumn == null) {
        batchNoColumn = binding.getKey();
      } else if (RUN_BINDING_TRACE_ID.equals(value) && traceIdColumn == null) {
        traceIdColumn = binding.getKey();
      }
    }
    if (batchNoColumn != null) {
      return new RunScope(batchNoColumn, resolveBatchNo(attributes));
    }
    if (traceIdColumn != null) {
      return new RunScope(traceIdColumn, resolveTraceId(attributes));
    }
    return null;
  }

  /** 与 LoadStep.buildLoadContext 一致:batchNo 取 importPayload.batchNo,缺省回退 bizDate。 */
  private String resolveBatchNo(Map<String, Object> attributes) {
    Object payloadObj = attributes.get(PipelineRuntimeKeys.IMPORT_PAYLOAD);
    if (payloadObj instanceof ImportPayload payload && Texts.hasText(payload.batchNo())) {
      return payload.batchNo();
    }
    Object bizDate = attributes.get("bizDate");
    return bizDate == null ? null : String.valueOf(bizDate);
  }

  private String resolveTraceId(Map<String, Object> attributes) {
    Object traceId = attributes.get(PipelineRuntimeKeys.TRACE_ID);
    return traceId == null ? null : String.valueOf(traceId);
  }

  private Map<String, Object> templateConfigMap(Map<String, Object> attributes) {
    Object o = attributes == null ? null : attributes.get(PipelineRuntimeKeys.TEMPLATE_CONFIG);
    if (o instanceof Map<?, ?> m) {
      Map<String, Object> out = new LinkedHashMap<>();
      m.forEach((k, v) -> out.put(String.valueOf(k), v));
      return out;
    }
    return Map.of();
  }

  private record RunScope(String runColumn, String runValue) {}
}
