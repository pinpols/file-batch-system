package com.example.batch.orchestrator.application.service.dataquality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.orchestrator.application.service.dataquality.DataQualityGateOutcome.GateStatus;
import com.example.batch.orchestrator.domain.entity.DataQualityCheckEntity;
import com.example.batch.orchestrator.domain.entity.DataQualityRuleEntity;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.mapper.DataQualityCheckMapper;
import com.example.batch.orchestrator.mapper.DataQualityRuleMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class DataQualityCheckExecutorTest {

  private DataQualityRuleMapper ruleMapper;
  private DataQualityCheckMapper checkMapper;
  private NamedParameterJdbcTemplate jdbcTemplate;
  private DataQualityCheckExecutor executor;

  @BeforeEach
  void setUp() {
    ruleMapper = mock(DataQualityRuleMapper.class);
    checkMapper = mock(DataQualityCheckMapper.class);
    jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<NamedParameterJdbcTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(jdbcTemplate);
    executor = new DataQualityCheckExecutor(ruleMapper, checkMapper, provider);
  }

  @Test
  void noRulesReturnsNoRulesStatus() {
    when(ruleMapper.selectEnabledByBusinessKey("t1", "job:JOB:2026-05-07")).thenReturn(List.of());

    var outcome = executor.execute(instance("t1", 1L), "job:JOB:2026-05-07");

    assertThat(outcome.status()).isEqualTo(GateStatus.NO_RULES);
    verify(checkMapper, never()).insert(any());
  }

  @Test
  void tableLevelPassWithMinThresholdEmitsPass() {
    DataQualityRuleEntity rule =
        rule(
            "ROW_COUNT_OK",
            "TABLE_LEVEL",
            "BLOCKER",
            "SELECT count(*) FROM batch.batch_day_instance WHERE tenant_id = :tenantId",
            "{\"min\":1}");
    when(ruleMapper.selectEnabledByBusinessKey(anyString(), anyString())).thenReturn(List.of(rule));
    when(jdbcTemplate.queryForObject(
            anyString(), any(MapSqlParameterSource.class), eq(Number.class)))
        .thenReturn(5);

    var outcome = executor.execute(instance("t1", 1L), "job:JOB:2026-05-07");

    assertThat(outcome.status()).isEqualTo(GateStatus.PASS);
    verify(checkMapper).insert(any(DataQualityCheckEntity.class));
  }

  @Test
  void tableLevelBlockerFailGatesEffective() {
    DataQualityRuleEntity rule =
        rule(
            "ROW_COUNT_LOW",
            "TABLE_LEVEL",
            "BLOCKER",
            "SELECT count(*) FROM batch.batch_day_instance",
            "{\"min\":100}");
    when(ruleMapper.selectEnabledByBusinessKey(anyString(), anyString())).thenReturn(List.of(rule));
    when(jdbcTemplate.queryForObject(
            anyString(), any(MapSqlParameterSource.class), eq(Number.class)))
        .thenReturn(5);

    var outcome = executor.execute(instance("t1", 1L), "job:JOB:2026-05-07");

    assertThat(outcome.status()).isEqualTo(GateStatus.BLOCKED);
    assertThat(outcome.findings()).hasSize(1);
    assertThat(outcome.findings().get(0).status()).isEqualTo("FAIL");
  }

  @Test
  void warnSeverityFailDowngradesToWarnNotBlocked() {
    DataQualityRuleEntity rule =
        rule("FRESHNESS", "TABLE_LEVEL", "WARN", "SELECT 0", "{\"min\":1}");
    when(ruleMapper.selectEnabledByBusinessKey(anyString(), anyString())).thenReturn(List.of(rule));
    when(jdbcTemplate.queryForObject(
            anyString(), any(MapSqlParameterSource.class), eq(Number.class)))
        .thenReturn(0);

    var outcome = executor.execute(instance("t1", 1L), "job:JOB:2026-05-07");

    assertThat(outcome.status()).isEqualTo(GateStatus.WARN);
  }

  @Test
  void rejectsDdlExpression() {
    DataQualityRuleEntity rule =
        rule("BAD_DDL", "TABLE_LEVEL", "BLOCKER", "DROP TABLE batch.batch_day_instance", null);
    when(ruleMapper.selectEnabledByBusinessKey(anyString(), anyString())).thenReturn(List.of(rule));

    var outcome = executor.execute(instance("t1", 1L), "job:JOB:2026-05-07");

    // 非 SELECT 抛 IllegalArgumentException → 单条 finding 记 ERROR + BLOCKER → 整体 BLOCKED
    assertThat(outcome.status()).isEqualTo(GateStatus.BLOCKED);
    assertThat(outcome.findings().get(0).status()).isEqualTo("ERROR");
  }

  @Test
  void rowLevelRuleSkippedForNowAsPass() {
    DataQualityRuleEntity rule = rule("ROW_AMT_POS", "ROW_LEVEL", "BLOCKER", "amount > 0", null);
    when(ruleMapper.selectEnabledByBusinessKey(anyString(), anyString())).thenReturn(List.of(rule));

    var outcome = executor.execute(instance("t1", 1L), "job:JOB:2026-05-07");

    // ROW_LEVEL v1.0 走 SPI sink，executor 端记 SKIPPED；不阻 EFFECTIVE
    assertThat(outcome.status()).isEqualTo(GateStatus.PASS);
    assertThat(outcome.findings().get(0).status()).isEqualTo("SKIPPED");
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static JobInstanceEntity instance(String tenantId, Long id) {
    JobInstanceEntity inst = new JobInstanceEntity();
    inst.setTenantId(tenantId);
    inst.setId(id);
    inst.setJobCode("JOB");
    inst.setBizDate(LocalDate.of(2026, 5, 7));
    inst.setInstanceStatus("SUCCESS");
    return inst;
  }

  private static DataQualityRuleEntity rule(
      String code, String type, String severity, String expr, String thresholdJson) {
    DataQualityRuleEntity r = new DataQualityRuleEntity();
    r.setId(1L);
    r.setTenantId("t1");
    r.setRuleCode(code);
    r.setRuleName(code);
    r.setRuleType(type);
    r.setSeverity(severity);
    r.setExpression(expr);
    r.setThresholdJson(thresholdJson);
    r.setEnabled(true);
    return r;
  }
}
