package io.github.pinpols.batch.orchestrator.infrastructure.dataquality;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.service.dataquality.DataQualitySqlRuleProbe;
import io.github.pinpols.batch.orchestrator.application.service.sensor.SensorSqlValidator;
import io.github.pinpols.batch.orchestrator.domain.entity.DataQualityRuleEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** JDBC 版 DQ SQL 规则执行器。 */
@Component
@RequiredArgsConstructor
public class JdbcDataQualitySqlRuleProbe implements DataQualitySqlRuleProbe {

  private static final List<String> ALLOWED_DQ_SCHEMAS = List.of("batch", "archive");

  private final ObjectProvider<NamedParameterJdbcTemplate> jdbcTemplateProvider;

  @Override
  public long evaluateScalar(JobInstanceEntity instance, DataQualityRuleEntity rule) {
    NamedParameterJdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
    if (EmptyChecks.isNull(jdbcTemplate)) {
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
    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("tenantId", instance.getTenantId())
        .addValue("bizDate", instance.getBizDate())
        .addValue("jobInstanceId", instance.getId());
    Number result;
    try {
      result = jdbcTemplate.queryForObject(validated, params, Number.class);
    } catch (DataAccessException dae) {
      throw new IllegalStateException("DQ SQL execution failed: " + dae.getMessage(), dae);
    }
    return EmptyChecks.isNull(result) ? 0L : result.longValue();
  }
}
