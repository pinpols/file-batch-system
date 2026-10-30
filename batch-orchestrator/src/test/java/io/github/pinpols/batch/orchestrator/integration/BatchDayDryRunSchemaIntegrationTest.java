package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplayPreviewTokenMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("整批量日演练会话与条目的数据契约及冷热表镜像,验证受支持候选形态可写入与非法组合被拒绝")
class BatchDayDryRunSchemaIntegrationTest extends AbstractIntegrationTest {

  private static final String DELETE_REPLAY_SESSION_SQL =
      "delete from batch.batch_day_replay_session where tenant_id = ? and id = ?";
  private static final String INSERT_REPLAY_SESSION_SQL = """
      insert into batch.batch_day_replay_session (
        tenant_id, calendar_code, biz_date, scope, execution_mode, candidate_source,
        result_policy, config_version_policy, reason, status, requested_by
      ) values (?, ?, ?, ?, ?, ?, ?, 'USE_ORIGINAL_CONFIG', 'schema-it', 'PENDING_APPROVAL', 'schema-it')
      returning id
      """;

  private static final String TENANT = "dry-run-schema-it";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 9, 11);

  private final JdbcTemplate jdbcTemplate;
  private final BatchDayReplayPreviewTokenMapper previewTokenMapper;

  @Autowired
  BatchDayDryRunSchemaIntegrationTest(
      JdbcTemplate jdbcTemplate, BatchDayReplayPreviewTokenMapper previewTokenMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.previewTokenMapper = previewTokenMapper;
  }

  @Test
  @DisplayName("归档镜像表与批量日重放会话表,条目表保持一致的演练相关列结构")
  void shouldMirrorDryRunColumnsInArchiveTables() {
    assertThat(columnsOf("batch", "batch_day_replay_session"))
        .contains("execution_mode", "candidate_source");
    assertThat(columnsOf("archive", "batch_day_replay_session_archive"))
        .contains("execution_mode", "candidate_source");
    assertThat(columnsOf("batch", "batch_day_replay_entry")).contains("plan_snapshot");
    assertThat(columnsOf("archive", "batch_day_replay_entry_archive")).contains("plan_snapshot");
    assertThat(columnsOf("batch", "batch_day_replay_preview_token"))
        .contains(
            "tenant_id",
            "token_hash",
            "request_hash",
            "snapshot_hash",
            "expires_at",
            "consumed_at",
            "session_id");
  }

  @Test
  @DisplayName("历史实例,产出结果和计划快照三类候选来源按各自约束写入时均成功落库")
  void shouldAcceptAllSupportedCandidateSourceShapes() {
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
  @DisplayName("重放条目必须与父会话属于同一租户")
  void shouldRejectReplayEntryWhoseTenantDiffersFromSession() {
    long sessionId =
        insertSession("cross-tenant-entry", "REPLAY", "EXISTING_INSTANCES", "KEEP_BOTH");

    assertThatThrownBy(
            () -> insertEntry(sessionId, "different-tenant", "CROSS_TENANT", 101L, null, null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasRootCauseInstanceOf(SQLException.class);
  }

  @Test
  @DisplayName("已有条目时父会话仍受原有删除保护")
  void shouldRejectDeletingReplaySessionWhileEntriesRemain() {
    long sessionId = insertSession("parent-delete", "REPLAY", "EXISTING_INSTANCES", "KEEP_BOTH");
    insertEntry(sessionId, "KEEP_PARENT", 101L, null, null);

    assertThatThrownBy(() -> jdbcTemplate.update(DELETE_REPLAY_SESSION_SQL, TENANT, sessionId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasRootCauseInstanceOf(SQLException.class);
  }

  @Test
  @DisplayName("预览令牌只能链接到同租户的重放会话")
  void shouldRejectPreviewTokenLinkedToSessionOfDifferentTenant() {
    long sessionId =
        insertSession("cross-tenant-token", "REPLAY", "EXISTING_INSTANCES", "KEEP_BOTH");

    assertThatThrownBy(() -> insertPreviewToken("different-tenant", "a".repeat(64), sessionId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasRootCauseInstanceOf(SQLException.class);
  }

  @Test
  @DisplayName("预览令牌 Mapper 不得跨租户链接会话")
  void shouldSkipPreviewTokenMapperLinkWhenSessionTenantDiffers() {
    long sessionId =
        insertSession("mapper-cross-tenant-token", "REPLAY", "EXISTING_INSTANCES", "KEEP_BOTH");
    String tokenHash = "d".repeat(64);
    insertPreviewToken("different-tenant", tokenHash, null);

    assertThat(previewTokenMapper.linkSession("different-tenant", tokenHash, sessionId))
        .isZero();
    String selectPreviewTokenSessionSql = """
        select session_id from batch.batch_day_replay_preview_token
         where tenant_id = ? and token_hash = ?
        """;
    Long selectedSessionId = jdbcTemplate.queryForObject(
        selectPreviewTokenSessionSql, Long.class, "different-tenant", tokenHash);
    assertThat(selectedSessionId).isNull();
  }

  @Test
  @DisplayName("保留中的预览令牌阻止删除其重放会话")
  void shouldRejectDeletingReplaySessionWhilePreviewTokenRemains() {
    long sessionId = insertSession("retained-token", "REPLAY", "EXISTING_INSTANCES", "KEEP_BOTH");
    insertPreviewToken(TENANT, "e".repeat(64), sessionId);

    assertThatThrownBy(() -> jdbcTemplate.update(DELETE_REPLAY_SESSION_SQL, TENANT, sessionId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasRootCauseInstanceOf(SQLException.class);
  }

  @Test
  @DisplayName("清理过期预览令牌后才允许删除重放会话")
  void shouldDeleteExpiredPreviewTokenBeforeItsReplaySession() {
    long sessionId =
        insertSession("expired-token-cleanup", "REPLAY", "EXISTING_INSTANCES", "KEEP_BOTH");
    String tokenHash = "e".repeat(64);
    String insertExpiredPreviewTokenSql = """
        insert into batch.batch_day_replay_preview_token (
          tenant_id, token_hash, request_hash, snapshot_hash, expires_at, consumed_at, session_id
        ) values (?, ?, ?, ?, now() - interval '25 hours', now() - interval '25 hours', ?)
        """;
    jdbcTemplate.update(
        insertExpiredPreviewTokenSql, TENANT, tokenHash, "f".repeat(64), "0".repeat(64), sessionId);

    assertThat(previewTokenMapper.deleteExpired(
            Instant.now(), Instant.now().minus(Duration.ofHours(24)), 10))
        .isEqualTo(1);
    int affected_REPLAY_SESSION_Rows =
        jdbcTemplate.update(DELETE_REPLAY_SESSION_SQL, TENANT, sessionId);
    assertThat(affected_REPLAY_SESSION_Rows).isEqualTo(1);
  }

  @Test
  @DisplayName("候选条目缺少来源实例,产出结果和计划快照时违反数据完整性约束")
  void shouldRejectCandidateWithoutAnySourceReference() {
    long sessionId = insertSession("empty", "REPLAY", "EXISTING_INSTANCES", "KEEP_BOTH");

    assertThatThrownBy(() -> insertEntry(sessionId, "EMPTY", null, null, null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasRootCauseInstanceOf(SQLException.class);
  }

  @Test
  @DisplayName("演练模式会话搭配创建新版本的结果策略时违反数据完整性约束")
  void shouldRejectDryRunSessionWithPromotionResultPolicy() {
    assertThatThrownBy(() ->
            insertSession("invalid-policy", "DRY_RUN", "EXISTING_INSTANCES", "CREATE_NEW_VERSION"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasRootCauseInstanceOf(SQLException.class);
  }

  @Test
  @DisplayName("演练模式会话使用仅产出范围时违反数据完整性约束")
  void shouldRejectDryRunSessionWithOutputsOnlyScope() {
    String insertOutputsOnlySessionSql = INSERT_REPLAY_SESSION_SQL;
    assertThatThrownBy(() -> jdbcTemplate.queryForObject(
            insertOutputsOnlySessionSql,
            Long.class,
            TENANT,
            "dry-run-outputs",
            BIZ_DATE,
            "OUTPUTS_ONLY",
            "DRY_RUN",
            "EXISTING_INSTANCES",
            "DRY_RUN_ONLY"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasRootCauseInstanceOf(SQLException.class);
  }

  private long insertSession(
      String suffix, String executionMode, String candidateSource, String resultPolicy) {
    final String insertReplaySessionSql = INSERT_REPLAY_SESSION_SQL;
    Long id = jdbcTemplate.queryForObject(
        insertReplaySessionSql,
        Long.class,
        TENANT,
        "calendar-" + suffix,
        BIZ_DATE,
        "ALL",
        executionMode,
        candidateSource,
        resultPolicy);
    assertThat(id).isNotNull();
    return id;
  }

  private void insertEntry(
      long sessionId,
      String jobCode,
      Long sourceInstanceId,
      Long resultVersionId,
      String planSnapshot) {
    insertEntry(sessionId, TENANT, jobCode, sourceInstanceId, resultVersionId, planSnapshot);
  }

  private void insertEntry(
      long sessionId,
      String tenantId,
      String jobCode,
      Long sourceInstanceId,
      Long resultVersionId,
      String planSnapshot) {
    final String insertReplayEntrySql = """
    insert into batch.batch_day_replay_entry (
    session_id, tenant_id, job_code, source_instance_id, result_version_id,
    plan_snapshot, status
    ) values (?, ?, ?, ?, ?, cast(? as jsonb), 'PENDING')
    """;
    jdbcTemplate.update(
        insertReplayEntrySql,
        sessionId,
        tenantId,
        jobCode,
        sourceInstanceId,
        resultVersionId,
        planSnapshot);
  }

  private void insertPreviewToken(String tenantId, String tokenHash, Long sessionId) {
    String insertPreviewTokenSql = """
        insert into batch.batch_day_replay_preview_token (
          tenant_id, token_hash, request_hash, snapshot_hash, expires_at, consumed_at, session_id
        ) values (?, ?, ?, ?, now() + interval '5 minutes', now(), ?)
        """;
    jdbcTemplate.update(
        insertPreviewTokenSql, tenantId, tokenHash, "b".repeat(64), "c".repeat(64), sessionId);
  }

  private int entryCount(long sessionId) {
    String countReplayEntriesSql = """
        select count(*) from batch.batch_day_replay_entry where session_id = ?
    """;
    Integer count = jdbcTemplate.queryForObject(countReplayEntriesSql, Integer.class, sessionId);
    return count == null ? 0 : count;
  }

  private List<String> columnsOf(String schema, String table) {
    String selectTableColumnsSql = """
        select column_name from information_schema.columns
         where table_schema = ? and table_name = ? order by ordinal_position
        """;
    return jdbcTemplate.queryForList(selectTableColumnsSql, String.class, schema, table);
  }
}
