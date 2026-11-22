package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 用真实 PostgreSQL 校验增量水位查询: 空批成功、归档、补跑、重放、部分失败与租户/作业隔离。
 *
 * <p>审计最小对照只证明「最新成功实例 OUT 为 NULL 时会遮住上一轮水位」;这里覆盖修复后的完整查询口径。
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("增量水位 Mapper 的真实查询语义")
class IncrementalWatermarkMapperIntegrationTest {

  private static final String STATEMENT =
      "io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper"
          + ".selectMaxEffectiveHighWaterMarkOut";

  @Container
  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.platform();

  private static SqlSessionFactory sessions;
  private static JdbcTemplate jdbc;

  private String tenantId;
  private String otherTenantId;
  private Long jobDefinitionId;
  private Long otherDefinitionId;

  @BeforeAll
  static void migrateAndConfigureMapper() throws Exception {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas("batch", "quartz")
        .defaultSchema("batch")
        .locations("classpath:db/migration")
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        .load()
        .migrate();
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    jdbc = new JdbcTemplate(source);
    Configuration config =
        new Configuration(new Environment("watermark", new JdbcTransactionFactory(), source));
    for (String mapper :
        List.of("mapper/JobInstanceMapper.xml", "mapper/SuccessInstanceArchiveMapper.xml")) {
      try (var input = Resources.getResourceAsStream(mapper)) {
        new XMLMapperBuilder(input, config, mapper, config.getSqlFragments()).parse();
      }
    }
    sessions = new SqlSessionFactoryBuilder().build(config);
  }

  @BeforeEach
  void seedDefinitions() {
    tenantId = "tenant-" + UUID.randomUUID();
    otherTenantId = "tenant-" + UUID.randomUUID();
    jobDefinitionId = insertDefinition(tenantId, "INC_WATERMARK_JOB");
    otherDefinitionId = insertDefinition(tenantId, "INC_WATERMARK_OTHER");
  }

  @Test
  @DisplayName("空批成功不回落水位: 42 → 空批 → 新数据 43,第三轮 IN 仍是 42")
  void emptySuccessBatch_keepsPreviousWatermark() {
    insertSuccess("42", 3);
    assertThat(latestEffectiveWatermark()).isEqualTo("42");

    insertSuccess(null, 2);
    assertThat(latestEffectiveWatermark()).as("空批成功没有新 OUT,不能遮住上一轮 42").isEqualTo("42");

    insertSuccess("43", 1);
    assertThat(latestEffectiveWatermark()).isEqualTo("43");
  }

  @Test
  @DisplayName("晚完成的较低 OUT 不回退后续 IN: 50 先完成、40 后完成仍取 50")
  void lateLowerOut_doesNotRegressWatermark() {
    insertSuccess("50", 2);
    insertSuccess("40", 1);

    assertThat(latestEffectiveWatermark()).as("较晚完成但 OUT 更低,不能把下一轮 IN 拉回 40").isEqualTo("50");
  }

  @Test
  @DisplayName("热表与归档各取最大 OUT 后合并")
  void shouldTakeMaxAcrossHotAndArchive() {
    Long archived = insertSuccess("60", 5);
    archiveInstance(archived);
    insertSuccess("50", 1);

    assertThat(latestEffectiveWatermark()).isEqualTo("60");
  }

  @Test
  @DisplayName("首次运行、只有空批成功或 OUT 为空串时没有可用水位")
  void returnsNull_whenNoInstanceReportedWatermark() {
    assertThat(latestEffectiveWatermark()).isNull();

    insertSuccess(null, 2);
    insertSuccess("", 1);
    assertThat(latestEffectiveWatermark()).isNull();
  }

  @Test
  @DisplayName("晚完成的更低水位按最新完整成功取值,补跑/重放/部分失败不参与")
  void ignoresSuccessInstances_withoutAdvanceEligibility() {
    insertSuccess("43", 3);
    insertInstance("PARTIAL_FAILED", "99", null, false, 2);
    insertInstance("SUCCESS_DRY_RUN", "100", null, false, 1);
    insertInstance("SUCCESS", "101", 7L, false, 1);
    insertInstance("SUCCESS", "102", null, true, 1);

    assertThat(latestEffectiveWatermark()).as("部分失败、dry-run、历史重放、补跑都不能推进正式增量水位").isEqualTo("43");
  }

  @Test
  @DisplayName("成功实例归档到冷表后水位仍可追溯")
  void followsWatermark_acrossArchive() {
    Long archivedInstance = insertSuccess("42", 5);
    insertSuccess(null, 3);
    archiveInstance(archivedInstance);

    assertThat(jdbc.queryForObject(
            "select count(*) from batch.job_instance where id = ?", Long.class, archivedInstance))
        .isZero();

    assertThat(latestEffectiveWatermark()).as("热表只剩空批时仍取归档中的 42").isEqualTo("42");
  }

  @Test
  @DisplayName("归档行与新热行之间仍按完成时间取最新")
  void picksNewerHotInstance_overArchivedOne() {
    Long archivedInstance = insertSuccess("42", 5);
    archiveInstance(archivedInstance);
    insertSuccess("43", 1);

    assertThat(latestEffectiveWatermark()).isEqualTo("43");
  }

  @Test
  @DisplayName("水位按租户与作业定义隔离,不会读到其他数据流")
  void isolates_byTenantAndJobDefinition() {
    insertSuccess("42", 1);

    assertThat(selectWatermark(tenantId, otherDefinitionId)).isNull();
    assertThat(selectWatermark(otherTenantId, jobDefinitionId)).isNull();
    assertThat(selectWatermark(tenantId, jobDefinitionId)).isEqualTo("42");
  }

  private String latestEffectiveWatermark() {
    return selectWatermark(tenantId, jobDefinitionId);
  }

  private String selectWatermark(String tenant, Long definitionId) {
    try (SqlSession session = sessions.openSession()) {
      return session.selectOne(
          STATEMENT, Map.of("tenantId", tenant, "jobDefinitionId", definitionId));
    }
  }

  private Long insertDefinition(String tenant, String jobCode) {
    return jdbc.queryForObject("""
        insert into batch.job_definition(
          tenant_id, job_code, job_name, job_type, schedule_type, timezone, calendar_code)
        values (?, ?, 'Incremental watermark', 'GENERAL', 'MANUAL', 'UTC', 'CAL')
        returning id
        """, Long.class, tenant, jobCode);
  }

  private Long insertSuccess(String watermark, int finishedHoursAgo) {
    return insertInstance("SUCCESS", watermark, null, false, finishedHoursAgo);
  }

  private Long insertInstance(
      String status, String watermark, Long replaySessionId, boolean rerun, int finishedHoursAgo) {
    return jdbc.queryForObject(
        """
        insert into batch.job_instance(
          tenant_id, job_definition_id, job_code, instance_no, biz_date, trigger_type,
          instance_status, dedup_key, rerun_flag, high_water_mark_out, replay_session_id,
          created_at, finished_at)
        values (?, ?, 'INC_WATERMARK_JOB', ?, ?, 'MANUAL', ?, ?, ?, ?, ?,
                now() - (? * interval '1 hour'), now() - (? * interval '1 hour'))
        returning id
        """,
        Long.class,
        tenantId,
        jobDefinitionId,
        "inst-" + UUID.randomUUID(),
        LocalDate.now(),
        status,
        "dedup-" + UUID.randomUUID(),
        rerun,
        watermark,
        replaySessionId,
        finishedHoursAgo,
        finishedHoursAgo);
  }

  private void archiveInstance(Long instanceId) {
    try (SqlSession session = sessions.openSession(true)) {
      SuccessInstanceArchiveMapper mapper = session.getMapper(SuccessInstanceArchiveMapper.class);
      mapper.archiveJobInstancesByIds(List.of(instanceId));
      mapper.deleteJobInstancesByIds(List.of(instanceId));
    }
  }
}
