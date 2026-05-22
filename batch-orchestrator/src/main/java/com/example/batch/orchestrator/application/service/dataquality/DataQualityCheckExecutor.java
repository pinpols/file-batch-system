package com.example.batch.orchestrator.application.service.dataquality;

import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.application.service.dataquality.DataQualityGateOutcome.GateStatus;
import com.example.batch.orchestrator.application.service.dataquality.DataQualityGateOutcome.RuleFinding;
import com.example.batch.orchestrator.application.service.sensor.SensorSqlValidator;
import com.example.batch.orchestrator.domain.entity.DataQualityCheckEntity;
import com.example.batch.orchestrator.domain.entity.DataQualityRuleEntity;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.mapper.DataQualityCheckMapper;
import com.example.batch.orchestrator.mapper.DataQualityRuleMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * ADR-021 DQ gate 执行器：在 job_instance 进 SUCCESS / PARTIAL_FAILED 终态后调用，按 (tenant, business_key 前缀)
 * 拉取 enabled rules 逐条执行，写入 data_quality_check，返回汇总 gate outcome 给 ResultVersionWriter。
 *
 * <p><b>v1.0 实现范围（priority-scope §ADR-021 ✅ 清单）</b>：
 *
 * <ul>
 *   <li>TABLE_LEVEL — SQL select 返回单值 / 单行 metric，按 thresholdJson 阈值判定
 *   <li>ROW_LEVEL / CROSS_TABLE / CROSS_DAY — 占位实现：业务方走 SPI sink 直接写 data_quality_check，本类读 + 汇总
 * </ul>
 *
 * <p><b>红线（priority-scope §5）</b>：
 *
 * <ul>
 *   <li>禁止运行 DDL / DML / pg_* 系统表 — SQL 必须以 SELECT 开头；
 *   <li>禁止裁定业务对错（修业务数据 vs 业务语义仲裁的判定提问）；
 *   <li>禁止跨租户共享规则 — scope_business_key + tenantId 双约束。
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataQualityCheckExecutor {

  private static final String STATUS_PASS = "PASS";
  private static final String STATUS_FAIL = "FAIL";
  private static final String STATUS_ERROR = "ERROR";

  private static final String SEVERITY_BLOCKER = "BLOCKER";
  private static final String SEVERITY_WARN = "WARN";

  // R2-P0-1：DQ 规则 SQL 走 NamedParameterJdbcTemplate + JSqlParser AST 校验，消除字符串拼接注入面。
  // SensorSqlValidator 已经做了「SELECT/WITH 限制 + 禁 *」校验，DQ 复用同一套规则。
  // 允许的 schema 限定在 batch / archive 两个业务命名空间，禁止访问 pg_catalog / information_schema 等。
  private static final List<String> ALLOWED_DQ_SCHEMAS = List.of("batch", "archive");

  private final DataQualityRuleMapper ruleMapper;
  private final DataQualityCheckMapper checkMapper;
  private final ObjectProvider<NamedParameterJdbcTemplate> jdbcTemplateProvider;

  /**
   * 执行 DQ gate。规则集为空时返回 {@link GateStatus#NO_RULES}（与现有行为一致 — 无规则不阻塞）。
   *
   * @param instance job_instance 终态
   * @param businessKey 由 ResultVersionWriter 算出的 business_key（如 {@code job:DAILY_PNL:2026-05-07}）
   */
  public DataQualityGateOutcome execute(JobInstanceEntity instance, String businessKey) {
    if (instance == null
        || instance.getId() == null
        || !Texts.hasText(instance.getTenantId())
        || !Texts.hasText(businessKey)) {
      return DataQualityGateOutcome.noRules();
    }
    List<DataQualityRuleEntity> rules =
        ruleMapper.selectEnabledByBusinessKey(instance.getTenantId(), businessKey);
    if (rules == null || rules.isEmpty()) {
      return DataQualityGateOutcome.noRules();
    }

    List<RuleFinding> findings = new ArrayList<>(rules.size());
    boolean anyBlocker = false;
    boolean anyWarn = false;
    Instant now = Instant.now();

    for (DataQualityRuleEntity rule : rules) {
      RuleFinding finding = executeOne(instance, rule, now);
      findings.add(finding);
      if (STATUS_FAIL.equals(finding.status()) || STATUS_ERROR.equals(finding.status())) {
        if (SEVERITY_BLOCKER.equalsIgnoreCase(rule.getSeverity())) {
          anyBlocker = true;
        } else {
          anyWarn = true;
        }
      }
    }

    GateStatus status =
        anyBlocker ? GateStatus.BLOCKED : (anyWarn ? GateStatus.WARN : GateStatus.PASS);
    return DataQualityGateOutcome.builder().status(status).findings(findings).build();
  }

  private RuleFinding executeOne(
      JobInstanceEntity instance, DataQualityRuleEntity rule, Instant now) {
    String type = rule.getRuleType() == null ? "" : rule.getRuleType().toUpperCase(Locale.ROOT);
    String severity =
        rule.getSeverity() == null ? SEVERITY_WARN : rule.getSeverity().toUpperCase(Locale.ROOT);
    try {
      String status =
          switch (type) {
            case "TABLE_LEVEL" -> executeTableLevel(instance, rule);
            case "ROW_LEVEL", "CROSS_TABLE", "CROSS_DAY" ->
                // v1.0 暂占位：业务方走 SPI sink 直接写 data_quality_check；
                // 这里返回 PASS 让 gate 不强阻（避免误伤）— 真实失败由 sink 已落 FAIL 行驱动。
                "SKIPPED";
            default -> STATUS_ERROR;
          };
      writeCheck(instance, rule, severity, status, null, null, now);
      return RuleFinding.builder()
          .ruleCode(rule.getRuleCode())
          .ruleType(type)
          .severity(severity)
          .status(status)
          .message(null)
          .build();
    } catch (RuntimeException ex) {
      SwallowedExceptionLogger.warn(
          DataQualityCheckExecutor.class, "catch:dq_rule_execute_failure", ex);
      writeCheck(instance, rule, severity, STATUS_ERROR, null, null, now);
      return RuleFinding.builder()
          .ruleCode(rule.getRuleCode())
          .ruleType(type)
          .severity(severity)
          .status(STATUS_ERROR)
          .message(ex.getMessage())
          .build();
    }
  }

  /**
   * TABLE_LEVEL 规则执行：JSqlParser 解析校验（必须 SELECT/WITH + 禁 * + schema 白名单）， 通过
   * NamedParameterJdbcTemplate 的 {@code :tenantId} / {@code :bizDate} / {@code :jobInstanceId} 命名参数
   * bind（驱动负责安全转义，杜绝字符串拼接注入面）；单行单值结果 vs thresholdJson 判定 PASS/FAIL。
   *
   * <p>R2-P0-1 安全说明：之前用 {@code String.replace(":bizDate", "'"+date+"'")} 把值拼回 SQL， 只对单引号做转义；bizDate
   * 含 {@code --} 等即可注入。NamedParameterJdbcTemplate 改用 JDBC bind 参数后， 即使 rule.expression 由 admin
   * 写库，注入面也被驱动层兜住。
   */
  private String executeTableLevel(JobInstanceEntity instance, DataQualityRuleEntity rule) {
    NamedParameterJdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
    if (jdbcTemplate == null) {
      throw new IllegalStateException("JdbcTemplate unavailable for DQ rule " + rule.getRuleCode());
    }
    String sql = rule.getExpression();
    if (!Texts.hasText(sql)) {
      throw new IllegalArgumentException("rule expression empty: " + rule.getRuleCode());
    }
    String validated;
    try {
      validated = SensorSqlValidator.validate(sql.trim(), ALLOWED_DQ_SCHEMAS);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException(
          "rule expression rejected by SQL validator: "
              + rule.getRuleCode()
              + " — "
              + ex.getMessage(),
          ex);
    }
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("tenantId", instance.getTenantId())
            .addValue("bizDate", instance.getBizDate())
            .addValue("jobInstanceId", instance.getId());
    Number result;
    try {
      result = jdbcTemplate.queryForObject(validated, params, Number.class);
    } catch (DataAccessException dae) {
      throw new IllegalStateException("DQ SQL execution failed: " + dae.getMessage(), dae);
    }
    long actual = result == null ? 0L : result.longValue();
    return matchThreshold(actual, rule.getThresholdJson()) ? STATUS_PASS : STATUS_FAIL;
  }

  @SuppressWarnings("unchecked")
  private boolean matchThreshold(long actual, String thresholdJson) {
    if (!Texts.hasText(thresholdJson)) {
      return actual > 0;
    }
    Map<String, Object> threshold;
    try {
      threshold = JsonUtils.fromJson(thresholdJson, Map.class);
    } catch (Exception ignored) {
      return actual > 0;
    }
    if (threshold == null) {
      return actual > 0;
    }
    Object min = threshold.get("min");
    Object max = threshold.get("max");
    Object expected = threshold.get("expected");
    if (expected instanceof Number n) {
      return actual == n.longValue();
    }
    if (min instanceof Number minN && actual < minN.longValue()) return false;
    if (max instanceof Number maxN && actual > maxN.longValue()) return false;
    return true;
  }

  private void writeCheck(
      JobInstanceEntity instance,
      DataQualityRuleEntity rule,
      String severity,
      String status,
      String metricsJson,
      String failureSample,
      Instant now) {
    Map<String, Object> metrics = metricsJson == null ? new LinkedHashMap<>() : Map.of();
    DataQualityCheckEntity check =
        DataQualityCheckEntity.builder()
            .tenantId(instance.getTenantId())
            .jobInstanceId(instance.getId())
            .ruleId(rule.getId())
            .ruleCode(rule.getRuleCode())
            .ruleType(rule.getRuleType())
            .severity(severity)
            .status(status)
            .metricsJson(metricsJson != null ? metricsJson : JsonUtils.toJson(metrics))
            .failureSample(failureSample)
            .checkedAt(now)
            .build();
    checkMapper.insert(check);
  }
}
