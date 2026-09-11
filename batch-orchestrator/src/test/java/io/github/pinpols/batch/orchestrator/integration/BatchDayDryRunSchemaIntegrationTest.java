package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** V202 整批量日 dry-run 数据契约与冷热表镜像集成测试。 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class BatchDayDryRunSchemaIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "dry-run-schema-it";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 9, 11);

  private final JdbcTemplate jdbcTemplate;

  @Autowired
  BatchDayDryRunSchemaIntegrationTest(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Test
  void archiveMirrorContainsDryRunColumns() {
    assertThat(columnsOf("batch", "batch_day_replay_session"))
        .contains("execution_mode", "candidate_source");
    assertThat(columnsOf("archive", "batch_day_replay_session_archive"))
        .contains("execution_mode", "candidate_source");
    assertThat(columnsOf("batch", "batch_day_replay_entry")).contains("plan_snapshot");
    assertThat(columnsOf("archive", "batch_day_replay_entry_archive")).contains("plan_snapshot");
  }

  @Test
  void acceptsEverySupportedCandidateShape() {
    long historicalSession =
        insertSession("historical", "REPLAY", "EXISTING_INSTANCES", "KEEP_BOTH");
    long outputsSession =
        insertSession("outputs", "REPLAY", "EXISTING_INSTANCES", "MANUAL_CONFIRM_EFFECTIVE");
    long planSession = insertSession("plan", "DRY_RUN", "SCHEDULE_PLAN", "DRY_RUN_ONLY");

    insertEntry(historicalSession, "HISTORICAL", 101L, null, null);
    insertEntry(outputsSession, "OUTPUTS", null, 202L, null);
    insertEntry(planSession, "PLAN", null, null, "{\"jobCode\":\"PLAN\"}");

    assertThat(entryCount(historicalSession)).isEqualTo(1);
    assertThat(entryCount(outputsSession)).isEqualTo(1);
    assertThat(entryCount(planSession)).isEqualTo(1);
  }

  @Test
  void rejectsCandidateWithoutSourceVersionOrPlanSnapshot() {
    long sessionId = insertSession("empty", "REPLAY", "EXISTING_INSTANCES", "KEEP_BOTH");

    assertThatThrownBy(() -> insertEntry(sessionId, "EMPTY", null, null, null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasRootCauseInstanceOf(SQLException.class);
  }

  @Test
  void rejectsDryRunWithPromotionPolicy() {
    assertThatThrownBy(() ->
            insertSession("invalid-policy", "DRY_RUN", "EXISTING_INSTANCES", "CREATE_NEW_VERSION"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasRootCauseInstanceOf(SQLException.class);
  }

  @Test
  void rejectsDryRunOutputsOnlyScope() {
    assertThatThrownBy(() -> jdbcTemplate.queryForObject(
            sessionInsertSql("OUTPUTS_ONLY"),
            Long.class,
            TENANT,
            "dry-run-outputs",
            BIZ_DATE,
            "DRY_RUN",
            "EXISTING_INSTANCES",
            "DRY_RUN_ONLY"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasRootCauseInstanceOf(SQLException.class);
  }

  private long insertSession(
      String suffix, String executionMode, String candidateSource, String resultPolicy) {
    Long id = jdbcTemplate.queryForObject(
        sessionInsertSql("ALL"),
        Long.class,
        TENANT,
        "calendar-" + suffix,
        BIZ_DATE,
        executionMode,
        candidateSource,
        resultPolicy);
    assertThat(id).isNotNull();
    return id;
  }

  private static String sessionInsertSql(String scope) {
    return "insert into batch.batch_day_replay_session "
        + "(tenant_id, calendar_code, biz_date, scope, execution_mode, candidate_source, "
        + "result_policy, config_version_policy, reason, status, requested_by) "
        + "values (?, ?, ?, '" + scope + "', ?, ?, ?, 'USE_ORIGINAL_CONFIG', "
        + "'schema-it', 'PENDING_APPROVAL', 'schema-it') returning id";
  }

  private void insertEntry(
      long sessionId,
      String jobCode,
      Long sourceInstanceId,
      Long resultVersionId,
      String planSnapshot) {
    jdbcTemplate.update(
        "insert into batch.batch_day_replay_entry "
            + "(session_id, tenant_id, job_code, source_instance_id, result_version_id, "
            + "plan_snapshot, status) values (?, ?, ?, ?, ?, cast(? as jsonb), 'PENDING')",
        sessionId,
        TENANT,
        jobCode,
        sourceInstanceId,
        resultVersionId,
        planSnapshot);
  }

  private int entryCount(long sessionId) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from batch.batch_day_replay_entry where session_id = ?",
        Integer.class,
        sessionId);
    return count == null ? 0 : count;
  }

  private List<String> columnsOf(String schema, String table) {
    return jdbcTemplate.queryForList(
        "select column_name from information_schema.columns "
            + "where table_schema = ? and table_name = ? order by ordinal_position",
        String.class,
        schema,
        table);
  }
}
