package io.github.pinpols.batch.orchestrator.application.service.dataquality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.application.service.dataquality.DataQualityGateOutcome.GateStatus;
import io.github.pinpols.batch.orchestrator.config.DataQualityProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.DataQualityCheckEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.DataQualityRuleEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.dataquality.JdbcDataQualitySqlRuleProbe;
import io.github.pinpols.batch.orchestrator.mapper.DataQualityCheckMapper;
import io.github.pinpols.batch.orchestrator.mapper.DataQualityRuleMapper;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@DisplayName("数据质量检查执行器: 规则模式, 严重度判定与门禁状态口径")
class DataQualityCheckExecutorTest {

  private DataQualityRuleMapper ruleMapper;
  private DataQualityCheckMapper checkMapper;
  private NamedParameterJdbcTemplate jdbcTemplate;
  private DataQualityCheckExecutor executor;
  private DataQualityProperties properties;

  @BeforeEach
  void setUp() {
    ruleMapper = mock(DataQualityRuleMapper.class);
    checkMapper = mock(DataQualityCheckMapper.class);
    jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<NamedParameterJdbcTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(jdbcTemplate);
    properties = new DataQualityProperties();
    executor = new DataQualityCheckExecutor(
        ruleMapper, checkMapper, new JdbcDataQualitySqlRuleProbe(provider), properties);
  }

  @Test
  @DisplayName("没有启用规则时门禁状态为无规则, 不落库检查记录")
  void shouldReportNoRules_whenNoEnabledRules() {
    when(ruleMapper.selectEnabledByBusinessKey("t1", "job:JOB:2026-05-07")).thenReturn(List.of());

    var outcome = executor.execute(instance("t1", 1L), "job:JOB:2026-05-07");

    assertThat(outcome.status()).isEqualTo(GateStatus.NO_RULES);
    verify(checkMapper, never()).insert(any());
  }

  @Test
  @DisplayName("表级规则达到最小阈值时门禁通过并落库检查记录")
  void shouldPass_whenTableLevelRuleMeetsThreshold() {
    DataQualityRuleEntity rule = rule(
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
  @DisplayName("阻断级规则不达标时门禁阻断并记录失败明细")
  void shouldBlock_whenBlockerRuleFails() {
    DataQualityRuleEntity rule = rule(
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
  @DisplayName("告警级规则不达标时降级为告警而不阻断")
  void shouldWarn_whenWarnSeverityRuleFails() {
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
  @DisplayName("规则表达式不是查询语句时记错误明细并阻断")
  void shouldBlock_whenExpressionIsNotSelect() {
    DataQualityRuleEntity rule =
        rule("BAD_DDL", "TABLE_LEVEL", "BLOCKER", "DROP TABLE batch.batch_day_instance", null);
    when(ruleMapper.selectEnabledByBusinessKey(anyString(), anyString())).thenReturn(List.of(rule));

    var outcome = executor.execute(instance("t1", 1L), "job:JOB:2026-05-07");

    // 非 SELECT 抛 IllegalArgumentException → 单条 finding 记 ERROR + BLOCKER → 整体 BLOCKED
    assertThat(outcome.status()).isEqualTo(GateStatus.BLOCKED);
    assertThat(outcome.findings().get(0).status()).isEqualTo("ERROR");
  }

  @Test
  @DisplayName("规则表达式使用禁用函数时在校验阶段记错误并阻断, 不下发执行")
  void shouldBlock_whenExpressionUsesForbiddenFunction() {
    DataQualityRuleEntity rule = rule(
        "DOS_SLEEP",
        "TABLE_LEVEL",
        "BLOCKER",
        "SELECT pg_sleep(30) FROM batch.batch_day_instance",
        null);
    when(ruleMapper.selectEnabledByBusinessKey(anyString(), anyString())).thenReturn(List.of(rule));

    var outcome = executor.execute(instance("t1", 1L), "job:JOB:2026-05-07");

    // 禁用函数在校验阶段被拒 → ERROR + BLOCKER → BLOCKED，且从不下发到 DB 执行。
    assertThat(outcome.status()).isEqualTo(GateStatus.BLOCKED);
    assertThat(outcome.findings().get(0).status()).isEqualTo("ERROR");
    verify(jdbcTemplate, never())
        .queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Number.class));
  }

  @Test
  @DisplayName("行级规则本轮按跳过处理并记为跳过, 不影响门禁通过")
  void shouldSkipRowLevelRule_whenExecuting() {
    DataQualityRuleEntity rule = rule("ROW_AMT_POS", "ROW_LEVEL", "BLOCKER", "amount > 0", null);
    when(ruleMapper.selectEnabledByBusinessKey(anyString(), anyString())).thenReturn(List.of(rule));

    var outcome = executor.execute(instance("t1", 1L), "job:JOB:2026-05-07");

    // ROW_LEVEL v1.0 走 SPI sink，executor 端记 SKIPPED；不阻 EFFECTIVE
    assertThat(outcome.status()).isEqualTo(GateStatus.PASS);
    assertThat(outcome.findings().get(0).status()).isEqualTo("SKIPPED");
  }

  @Test
  @DisplayName("关闭模式下不加载也不执行规则, 不落库检查记录")
  void shouldNotLoadRules_whenModeOff() {
    properties.setMode(DataQualityProperties.Mode.OFF);

    var outcome = executor.execute(instance("t1", 1L), "job:JOB:2026-05-07");

    assertThat(outcome.status()).isEqualTo(GateStatus.NO_RULES);
    verify(ruleMapper, never()).selectEnabledByBusinessKey(anyString(), anyString());
    verify(checkMapper, never()).insert(any());
  }

  @Test
  @DisplayName("影子模式下记录失败明细但降级为告警, 不阻断生效")
  void shouldRecordFailWithoutBlocking_whenInShadowMode() {
    properties.setMode(DataQualityProperties.Mode.SHADOW);
    DataQualityRuleEntity rule =
        rule("CROSS_TOTAL", "CROSS_TABLE", "BLOCKER", "SELECT 0", "{\"min\":1}");
    when(ruleMapper.selectEnabledByBusinessKey(anyString(), anyString())).thenReturn(List.of(rule));
    when(jdbcTemplate.queryForObject(
            anyString(), any(MapSqlParameterSource.class), eq(Number.class)))
        .thenReturn(0);

    var outcome = executor.execute(instance("t1", 1L), "job:JOB:2026-05-07");

    assertThat(outcome.status()).isEqualTo(GateStatus.WARN);
    assertThat(outcome.findings().get(0).status()).isEqualTo("FAIL");
    verify(checkMapper).insert(any(DataQualityCheckEntity.class));
  }

  @Test
  @DisplayName("跨天规则走校验后的标量查询路径, 结果达标时门禁通过")
  void shouldPass_whenCrossDayRuleMatches() {
    DataQualityRuleEntity rule =
        rule("CROSS_DAY_TOTAL", "CROSS_DAY", "BLOCKER", "SELECT 5", "{\"expected\":5}");
    when(ruleMapper.selectEnabledByBusinessKey(anyString(), anyString())).thenReturn(List.of(rule));
    when(jdbcTemplate.queryForObject(
            anyString(), any(MapSqlParameterSource.class), eq(Number.class)))
        .thenReturn(5);

    var outcome = executor.execute(instance("t1", 1L), "job:JOB:2026-05-07");

    assertThat(outcome.status()).isEqualTo(GateStatus.PASS);
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static JobInstanceEntity instance(String tenantId, Long id) {
    JobInstanceEntity inst = new JobInstanceEntity();
    inst.setTenantId(tenantId);
    inst.setId(id);
    inst.setJobCode("JOB");
    inst.setBizDate(LocalDate.of(2026, Month.MAY, 7));
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
