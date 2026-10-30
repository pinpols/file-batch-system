package io.github.pinpols.batch.worker.core.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import io.github.pinpols.batch.worker.core.infrastructure.CreatePipelineInstanceParam;
import io.github.pinpols.batch.worker.core.infrastructure.FileAuditParam;
import io.github.pinpols.batch.worker.core.infrastructure.FileErrorRecordParam;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileAuditRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineRunRepository;
import io.github.pinpols.batch.worker.core.param.PlatformFileAuditInsertParam;
import io.github.pinpols.batch.worker.core.param.PlatformFileErrorInsertParam;
import io.github.pinpols.batch.worker.core.param.PlatformPipelineInsertParam;
import io.github.pinpols.batch.worker.core.param.PlatformStepRunFinishParam;
import io.github.pinpols.batch.worker.core.param.PlatformStepRunInsertParam;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@code PlatformFileRuntimeMapper.xml} 的 {@code selectSucceededStepCodes} 真 PG 回归护栏。
 *
 * <p>2026-07 语义修复的核心守卫:P1 阶段级续跑判定"可跳过"必须看**每个 stepCode 最新一次 run 的终态**, 而非"历史上曾成功过"。旧 SQL {@code
 * select distinct step_code where step_status='SUCCESS'} 会把 "SUCCESS 后重跑 FAILED"的 step 误判为可跳过 →
 * COMMIT 静默少发布。本类用真实 PG(Testcontainers)+ **真实 mapper XML**(经 MyBatis {@link XMLMapperBuilder}
 * 加载,非手抄 SQL)固化修复后的语义。
 *
 * <p>不走 {@code AbstractIntegrationTest}/Spring:worker-core 是库模块无 Spring Boot 启动类,照姊妹 {@code
 * ProcessStageSkipCrashResumeIntegrationTest} 的裸 PG + JdbcTemplate 惯例,只是额外用 MyBatis
 * SqlSessionFactory 直加载真 XML,以覆盖 SQL 真伪(窗口函数、tie-break、分区取最新)。
 */
@DisplayName("平台文件运行态Mapper：租户引用、步骤续跑、主键回填及错误审计持久化")
class PlatformFileRuntimeMapperStageSkipIntegrationTest {

  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.create();

  private JdbcTemplate jdbcTemplate;
  private SqlSessionFactory sqlSessionFactory;

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @BeforeEach
  void setUp() throws Exception {
    DataSource dataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);

    final String dropSql = """
        drop schema if exists batch cascade
    """;
    jdbcTemplate.execute(dropSql);
    final String createSql = """
        create schema batch
    """;
    jdbcTemplate.execute(createSql);
    // 与 V6 pipeline_step_run 列/约束对齐(去掉对 pipeline_instance 的 FK 以自成一体,SELECT 不触父表)。
    final String createBatchPipelineStepRunSql = """
        create table batch.pipeline_step_run (
          id                   bigserial primary key,
          pipeline_instance_id bigint       not null,
          step_code            varchar(128) not null,
          stage_code           varchar(64)  not null,
          run_seq              integer      not null default 1,
          step_status          varchar(32)  not null,
          input_summary        jsonb,
          output_summary       jsonb,
          error_code           varchar(64),
          error_message        varchar(1024),
          error_key            varchar(256),
          error_args           jsonb,
          duration_ms          bigint default 0,
          started_at           timestamptz,
          finished_at          timestamptz,
          constraint uk_pipeline_step_run unique (pipeline_instance_id, step_code, run_seq)
        )
        """;
    jdbcTemplate.execute(createBatchPipelineStepRunSql);
    final String createBatchFileRecordSql = """
        create table batch.file_record (
          id           bigint primary key,
          tenant_id    varchar(64),
          file_status  varchar(32) not null,
          metadata_json jsonb,
          updated_at   timestamptz
        )
        """;
    jdbcTemplate.execute(createBatchFileRecordSql);
    final String createBatchPipelineInstanceSql = """
        create table batch.pipeline_instance (
          id bigserial primary key,
          tenant_id varchar(64) not null,
          system_pipeline_code varchar(128),
          system_pipeline_version integer,
          job_code varchar(128) not null,
          pipeline_type varchar(32) not null,
          file_id bigint,
          related_job_instance_id bigint,
          current_stage varchar(64),
          last_success_stage varchar(64),
          run_status varchar(32) not null,
          trace_id varchar(128),
          started_at timestamptz,
          finished_at timestamptz,
          created_at timestamptz,
          updated_at timestamptz
        )
        """;
    jdbcTemplate.execute(createBatchPipelineInstanceSql);
    final String createSqlValue1 = """
        create unique index uk_pipeline_instance_related_job
          on batch.pipeline_instance(related_job_instance_id)
          where related_job_instance_id is not null
        """;
    jdbcTemplate.execute(createSqlValue1);
    final String createBatchJobInstanceSql = """
        create table batch.job_instance (
          id bigint primary key,
          tenant_id varchar(64) not null
        )
        """;
    jdbcTemplate.execute(createBatchJobInstanceSql);
    final String createBatchFileErrorRecordSql = """
        create table batch.file_error_record (
          id bigserial primary key, tenant_id varchar(64), file_id bigint,
          pipeline_instance_id bigint, pipeline_step_run_id bigint, record_no bigint,
          error_code varchar(128), error_message text, error_key varchar(128), error_args jsonb,
          error_stage varchar(64), is_skipped boolean, skip_action varchar(64), raw_record jsonb,
          source_row_num bigint, source_column varchar(128), created_at timestamptz
        )
        """;
    jdbcTemplate.execute(createBatchFileErrorRecordSql);
    final String createBatchFileAuditLogSql = """
        create table batch.file_audit_log (
          id bigserial primary key, tenant_id varchar(64), file_id bigint,
          operation_type varchar(64), operation_result varchar(32), operator_type varchar(32),
          operator_id varchar(64), trace_id varchar(128), evidence_ref varchar(512),
          detail_summary jsonb, created_at timestamptz
        )
        """;
    jdbcTemplate.execute(createBatchFileAuditLogSql);
    final String createSqlValue2 = """
        create unique index uk_file_audit_dispatch_completion_request
          on batch.file_audit_log (
            tenant_id, file_id, evidence_ref, (detail_summary ->> 'channelCode')
          )
          where operation_type = 'DISPATCH_COMPLETE'
            and evidence_ref is not null
            and detail_summary ->> 'channelCode' is not null
        """;
    jdbcTemplate.execute(createSqlValue2);

    Configuration configuration =
        new Configuration(new Environment("it", new JdbcTransactionFactory(), dataSource));
    try (InputStream xml = Resources.getResourceAsStream("mapper/PlatformFileRuntimeMapper.xml")) {
      new XMLMapperBuilder(
              xml,
              configuration,
              "mapper/PlatformFileRuntimeMapper.xml",
              configuration.getSqlFragments())
          .parse();
    }
    sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
  }

  @Test
  @DisplayName("中断终态步骤拒绝旧 Worker 的迟到成功写回，当前运行尝试仍可正常结束")
  void shouldRejectLateStepCompletion_whenPriorAttemptAlreadyInterrupted() {
    String seedSql = """
        insert into batch.pipeline_step_run
          (id, pipeline_instance_id, step_code, stage_code, run_seq, step_status, error_code, started_at)
        values (1, 100, 'LOAD', 'LOAD', 1, 'FAILED', 'PIPELINE_ATTEMPT_INTERRUPTED', current_timestamp),
               (2, 100, 'LOAD', 'LOAD', 2, 'RUNNING', null, current_timestamp)
        """;
    jdbcTemplate.update(seedSql);
    PlatformStepRunFinishParam completion =
        PlatformStepRunFinishParam.builder().stepRunId(1L).status("SUCCESS").build();
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      PlatformFileRuntimeMapper mapper = session.getMapper(PlatformFileRuntimeMapper.class);
      int staleRows = mapper.finishStepRun(completion);
      completion = completion.toBuilder().stepRunId(2L).build();
      int currentRows = mapper.finishStepRun(completion);
      int repeatedRows = mapper.finishStepRun(completion);
      assertThat(staleRows).isZero();
      assertThat(currentRows).isEqualTo(1);
      assertThat(repeatedRows).isZero();
    }
    String historySql =
        "select step_status || ':' || coalesce(error_code, '') from batch.pipeline_step_run order by id";
    List<String> history = jdbcTemplate.queryForList(historySql, String.class);
    assertThat(history).containsExactly("FAILED:PIPELINE_ATTEMPT_INTERRUPTED", "SUCCESS:");
  }

  @Test
  @DisplayName("Pipeline 文件绑定要求 Pipeline 与文件均属于调用租户")
  void bindFileToPipelineInstance_rejectsCrossTenantRows() {
    final String insertBatchPipelineInstanceSql = """
        insert into batch.pipeline_instance(id, tenant_id, job_code, pipeline_type, run_status)\s\
        values (1, 't1', 'JOB1', 'IMPORT', 'RUNNING'), (2, 't2', 'JOB2', 'IMPORT', 'RUNNING')\
        """;
    jdbcTemplate.update(insertBatchPipelineInstanceSql);
    final String insertBatchFileRecordSql = """
        insert into batch.file_record(id, tenant_id, file_status)\s\
        values (10, 't1', 'RECEIVED'), (20, 't2', 'RECEIVED')\
        """;
    jdbcTemplate.update(insertBatchFileRecordSql);

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      PlatformFileRuntimeMapper mapper = session.getMapper(PlatformFileRuntimeMapper.class);
      assertThat(mapper.bindFileToPipelineInstance("t1", 1L, 10L)).isEqualTo(1);
      assertThat(mapper.bindFileToPipelineInstance("t1", 1L, 20L)).isZero();
      assertThat(mapper.bindFileToPipelineInstance("t1", 2L, 10L)).isZero();
    }
    final String selectBatchPipelineInstanceSql = """
        select file_id from batch.pipeline_instance where id = 1
    """;
    Long selectedFileId = jdbcTemplate.queryForObject(selectBatchPipelineInstanceSql, Long.class);
    assertThat(selectedFileId).isEqualTo(10L);
    final String selectBatchPipelineInstanceSqlValue3 = """
        select file_id from batch.pipeline_instance where id = 2
    """;
    Long selectedFileIdRead2 =
        jdbcTemplate.queryForObject(selectBatchPipelineInstanceSqlValue3, Long.class);
    assertThat(selectedFileIdRead2).isNull();
  }

  @Test
  @DisplayName("归档锁住父实例期间迟到 Pipeline 写入等待并在父实例删除后拒绝")
  void shouldFencePipelineCreation_whenParentIsArchived() throws Exception {
    String insertParentSql = "insert into batch.job_instance(id, tenant_id) values (100, 't1')";
    String lockParentSql = "select id from batch.job_instance where id = 100 for update";
    String deleteParentSql = "delete from batch.job_instance where id = 100";
    String pidSql = "select pg_backend_pid()";
    String blockersSql = "select ? = any(pg_blocking_pids(?))";
    String countSql =
        "select count(*) from batch.pipeline_instance where related_job_instance_id = 100";
    jdbcTemplate.update(insertParentSql);
    AtomicReference<Integer> writerPid = new AtomicReference<>();
    try (ExecutorService executor = Executors.newSingleThreadExecutor();
        Connection archive =
            Objects.requireNonNull(jdbcTemplate.getDataSource()).getConnection()) {
      archive.setAutoCommit(false);
      int archivePid;
      try (PreparedStatement pid = archive.prepareStatement(pidSql);
          ResultSet result = pid.executeQuery()) {
        assertThat(result.next()).isTrue();
        archivePid = result.getInt(1);
      }
      try (PreparedStatement lock = archive.prepareStatement(lockParentSql);
          ResultSet result = lock.executeQuery()) {
        assertThat(result.next()).isTrue();
      }
      Future<Integer> insert = executor.submit(() -> {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
          try (PreparedStatement pid = session.getConnection().prepareStatement(pidSql);
              ResultSet result = pid.executeQuery()) {
            if (!result.next()) throw new IllegalStateException("missing backend pid");
            writerPid.set(result.getInt(1));
          }
          return session
              .getMapper(PlatformFileRuntimeMapper.class)
              .insertPipelineInstance(pipelineParams(null, 100L));
        }
      });
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      boolean blocked = false;
      while (System.nanoTime() < deadline && !insert.isDone()) {
        Integer pid = writerPid.get();
        Boolean waiting = pid == null
            ? false
            : jdbcTemplate.queryForObject(blockersSql, Boolean.class, archivePid, pid);
        if (Boolean.TRUE.equals(waiting)) {
          blocked = true;
          break;
        }
        Thread.sleep(10);
      }
      try (PreparedStatement delete = archive.prepareStatement(deleteParentSql)) {
        assertThat(delete.executeUpdate()).isEqualTo(1);
      }
      archive.commit();
      int inserted = insert.get(10, TimeUnit.SECONDS);
      assertThat(blocked).as("pipeline creation waits for the parent row lock").isTrue();
      assertThat(inserted).isZero();
      Integer count = jdbcTemplate.queryForObject(countSql, Integer.class);
      assertThat(count).isZero();
    }
  }

  @Test
  @DisplayName("Pipeline 实例创建要求文件和父 JobInstance 均属于相同租户")
  void insertPipelineInstance_rejectsCrossTenantReferences() {
    final String insertBatchFileRecordSqlValue4 = """
        insert into batch.file_record(id, tenant_id, file_status)\s\
        values (10, 't1', 'RECEIVED'), (20, 't2', 'RECEIVED')\
        """;
    jdbcTemplate.update(insertBatchFileRecordSqlValue4);
    final String insertBatchJobInstanceSql = """
        insert into batch.job_instance(id, tenant_id) values (100, 't1'), (200, 't2')
    """;
    jdbcTemplate.update(insertBatchJobInstanceSql);

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      PlatformFileRuntimeMapper mapper = session.getMapper(PlatformFileRuntimeMapper.class);
      assertThat(mapper.insertPipelineInstance(pipelineParams(10L, 100L))).isEqualTo(1);
      assertThat(mapper.insertPipelineInstance(pipelineParams(20L, 100L))).isZero();
      assertThat(mapper.insertPipelineInstance(pipelineParams(10L, 200L))).isZero();
    }
    final String selectBatchPipelineInstanceSqlValue5 = """
        select count(*) from batch.pipeline_instance
    """;
    Integer selectedPipelineInstanceCount =
        jdbcTemplate.queryForObject(selectBatchPipelineInstanceSqlValue5, Integer.class);
    assertThat(selectedPipelineInstanceCount).isEqualTo(1);
  }

  @Test
  @DisplayName("Pipeline 实例创建支持可选文件和父作业引用为空")
  void insertPipelineInstance_acceptsNullableReferences() {
    final String insertBatchFileRecordSqlValue6 = """
        insert into batch.file_record(id, tenant_id, file_status) values (10, 't1', 'RECEIVED')
    """;
    jdbcTemplate.update(insertBatchFileRecordSqlValue6);
    final String insertBatchJobInstanceSqlValue7 = """
        insert into batch.job_instance(id, tenant_id) values (100, 't1')
    """;
    jdbcTemplate.update(insertBatchJobInstanceSqlValue7);

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      PlatformFileRuntimeMapper mapper = session.getMapper(PlatformFileRuntimeMapper.class);
      assertThat(mapper.insertPipelineInstance(pipelineParams(null, 100L))).isEqualTo(1);
      assertThat(mapper.insertPipelineInstance(pipelineParams(10L, null))).isEqualTo(1);
      assertThat(mapper.insertPipelineInstance(pipelineParams(null, null))).isEqualTo(1);
    }

    final String selectBatchPipelineInstanceSqlValue8 = """
        select count(*) from batch.pipeline_instance
    """;
    Integer selectedPipelineInstanceCount =
        jdbcTemplate.queryForObject(selectBatchPipelineInstanceSqlValue8, Integer.class);
    assertThat(selectedPipelineInstanceCount).isEqualTo(3);
  }

  private static PlatformPipelineInsertParam pipelineParams(Long fileId, Long jobInstanceId) {
    return PlatformPipelineInsertParam.builder()
        .tenantId("t1")
        .systemPipelineCode("IMPORT")
        .systemPipelineVersion(1)
        .jobCode("JOB_TEST")
        .pipelineType("IMPORT")
        .fileId(fileId)
        .relatedJobInstanceId(jobInstanceId)
        .currentStage("RECEIVE")
        .runStatus("RUNNING")
        .traceId("trace-test")
        .build();
  }

  @Test
  @DisplayName("文件错误和审计记录拒绝跨租户文件及运行引用")
  void insertFileReferences_rejectsCrossTenantAssociations() {
    final String insertBatchFileRecordSqlValue9 = """
        insert into batch.file_record(id, tenant_id, file_status)\s\
        values (11, 't1', 'RECEIVED'), (21, 't2', 'RECEIVED')\
        """;
    jdbcTemplate.update(insertBatchFileRecordSqlValue9);
    final String insertBatchPipelineInstanceSqlValue10 = """
        insert into batch.pipeline_instance(id, tenant_id, job_code, pipeline_type, run_status)\s\
        values (101, 't1', 'JOB1', 'IMPORT', 'RUNNING'), (201, 't2', 'JOB2', 'IMPORT', 'RUNNING')\
        """;
    jdbcTemplate.update(insertBatchPipelineInstanceSqlValue10);
    final String insertBatchPipelineStepRunSql = """
        insert into batch.pipeline_step_run(id, pipeline_instance_id, step_code, stage_code, run_seq,\s\
        step_status) values (301, 101, 'RECEIVE', 'RECEIVE', 1, 'SUCCESS'), (401, 201, 'RECEIVE', 'RECEIVE', 1,\s\
        'SUCCESS')\
        """;
    jdbcTemplate.update(insertBatchPipelineStepRunSql);
    PlatformFileErrorInsertParam error = PlatformFileErrorInsertParam.builder()
        .tenantId("t1")
        .fileId(11L)
        .pipelineInstanceId(101L)
        .pipelineStepRunId(301L)
        .recordNo(1L)
        .isSkipped(false)
        .build();
    PlatformFileAuditInsertParam audit = PlatformFileAuditInsertParam.builder()
        .tenantId("t1")
        .fileId(11L)
        .operationType("TEST")
        .operationResult("SUCCESS")
        .operatorType("SYSTEM")
        .build();

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      PlatformFileRuntimeMapper mapper = session.getMapper(PlatformFileRuntimeMapper.class);
      assertThat(mapper.insertFileErrorRecord(error)).isEqualTo(1);
      error = error.toBuilder().fileId(21L).build();
      assertThat(mapper.insertFileErrorRecord(error)).isZero();
      error = error.toBuilder().fileId(11L).build();
      error = error.toBuilder().pipelineInstanceId(201L).build();
      error = error.toBuilder().pipelineStepRunId(401L).build();
      assertThat(mapper.insertFileErrorRecord(error)).isZero();
      assertThat(mapper.insertFileAuditLog(audit)).isEqualTo(1);
      audit = audit.toBuilder().fileId(21L).build();
      assertThat(mapper.insertFileAuditLog(audit)).isZero();
    }
    final String selectBatchFileErrorRecordSql = """
        select count(*) from batch.file_error_record
    """;
    Integer selectedFileErrorRecordCount =
        jdbcTemplate.queryForObject(selectBatchFileErrorRecordSql, Integer.class);
    assertThat(selectedFileErrorRecordCount).isEqualTo(1);
    final String selectBatchFileAuditLogSql = """
        select count(*) from batch.file_audit_log
    """;
    Integer selectedFileAuditLogCount =
        jdbcTemplate.queryForObject(selectBatchFileAuditLogSql, Integer.class);
    assertThat(selectedFileAuditLogCount).isEqualTo(1);
  }

  @Test
  @DisplayName("同一派发请求重复完成时审计只写一次, 不同渠道或请求仍保留独立审计")
  void insertDispatchCompletionAudit_isIdempotentPerRequestFileAndChannel() {
    final String insertBatchFileRecordSqlValue11 = """
        insert into batch.file_record(id, tenant_id, file_status) values (31, 't1', 'GENERATED')\
        """;
    jdbcTemplate.update(insertBatchFileRecordSqlValue11);
    PlatformFileAuditInsertParam audit = PlatformFileAuditInsertParam.builder()
        .tenantId("t1")
        .fileId(31L)
        .operationType("DISPATCH_COMPLETE")
        .operationResult("SUCCESS")
        .operatorType("SYSTEM")
        .evidenceRef("external-request-1")
        .detailSummaryJson("{\"channelCode\":\"channel-a\"}")
        .build();

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      PlatformFileRuntimeMapper mapper = session.getMapper(PlatformFileRuntimeMapper.class);
      assertThat(mapper.insertFileAuditLog(audit)).isEqualTo(1);
      assertThat(mapper.insertFileAuditLog(audit)).isZero();
      audit =
          audit.toBuilder().detailSummaryJson("{\"channelCode\":\"channel-b\"}").build();
      assertThat(mapper.insertFileAuditLog(audit)).isEqualTo(1);
      audit =
          audit.toBuilder().detailSummaryJson("{\"channelCode\":\"channel-a\"}").build();
      audit = audit.toBuilder().evidenceRef("external-request-2").build();
      assertThat(mapper.insertFileAuditLog(audit)).isEqualTo(1);
    }

    final String selectBatchFileAuditLogSqlValue12 = """
        select count(*) from batch.file_audit_log where tenant_id = 't1' and file_id = 31
    """;
    Integer selectedFileAuditLogCount =
        jdbcTemplate.queryForObject(selectBatchFileAuditLogSqlValue12, Integer.class);
    assertThat(selectedFileAuditLogCount).isEqualTo(3);
  }

  @Test
  @DisplayName("文件错误记录允许可选文件和运行引用为空")
  void insertFileErrorRecord_acceptsNullableReferences() {
    PlatformFileErrorInsertParam error = PlatformFileErrorInsertParam.builder()
        .tenantId("t1")
        .fileId(null)
        .pipelineInstanceId(null)
        .pipelineStepRunId(null)
        .recordNo(1L)
        .isSkipped(false)
        .build();

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      assertThat(session.getMapper(PlatformFileRuntimeMapper.class).insertFileErrorRecord(error))
          .isEqualTo(1);
    }

    final String selectBatchFileErrorRecordSqlValue13 = """
        select count(*) from batch.file_error_record
    """;
    Integer selectedFileErrorRecordCount =
        jdbcTemplate.queryForObject(selectBatchFileErrorRecordSqlValue13, Integer.class);
    assertThat(selectedFileErrorRecordCount).isEqualTo(1);
  }

  @Test
  @DisplayName("具名命令保留真实主键回填、步骤错误三元组、JSON和文件审计引用")
  void shouldPersistGeneratedIdsAndStructuredResultsWithTypedCommands() {
    String seedFileSql = """
        insert into batch.file_record(id, tenant_id, file_status) values (10, 't1', 'RECEIVED')
        """;
    jdbcTemplate.update(seedFileSql);
    Long pipelineId;
    Long stepRunId;
    Long errorId;
    try (SqlSession session = sqlSessionFactory.openSession(false)) {
      PlatformFileRuntimeMapper mapper = session.getMapper(PlatformFileRuntimeMapper.class);
      PlatformPipelineRunRepository runs = new PlatformPipelineRunRepository(mapper);
      PlatformFileAuditRepository audits = new PlatformFileAuditRepository(mapper);
      pipelineId = runs.createPipelineInstance(new CreatePipelineInstanceParam(
          "t1", "IMPORT", 1, "JOB_TYPED", "IMPORT", 10L, null, "RECEIVE", "trace-typed"));
      assertThat(pipelineId).isNotNull();
      session.commit();
      stepRunId = runs.startStepRun(pipelineId, "LOAD", "LOAD", Map.of("sourceRows", 2));
      assertThat(stepRunId).isNotNull();
      session.commit();

      runs.finishStepRunFailure(
          stepRunId,
          "LOAD_FAILED",
          "x".repeat(1100),
          "error.load.failed",
          "[\"amount\"]",
          Map.of("loadedRows", 1));
      runs.updatePipelineStage(pipelineId, "LOAD", "VALIDATE");
      runs.markPipelineCompensating(pipelineId);
      session.commit();
      String compensatingStatusSql = "select run_status from batch.pipeline_instance where id = ?";
      assertThat(jdbcTemplate.queryForObject(compensatingStatusSql, String.class, pipelineId))
          .isEqualTo("COMPENSATING");
      runs.markPipelineFailed(pipelineId, "LOAD", "VALIDATE");
      FileErrorRecordParam error = FileErrorRecordParam.builder()
          .tenantId("t1")
          .fileId(10L)
          .pipelineInstanceId(pipelineId)
          .pipelineStepRunId(stepRunId)
          .recordNo(2L)
          .errorCode("ROW_INVALID")
          .errorMessage("y".repeat(1100))
          .errorStage("LOAD")
          .skipped(true)
          .skipAction("SKIP_ROW")
          .rawRecord(Map.of("amount", "invalid"))
          .sourceRowNum(3L)
          .sourceColumn("amount")
          .build();
      errorId = audits.insertFileErrorRecord(error);
      assertThat(errorId).isNotNull();
      FileAuditParam audit = FileAuditParam.builder()
          .tenantId("t1")
          .fileId(10L)
          .operationType("TEST")
          .operationResult("SUCCESS")
          .detailSummary(Map.of("rows", 2))
          .build();
      audits.appendAudit(audit);
      session.commit();
    }

    String stepResultSql = """
        select step_status, run_seq, input_summary ->> 'sourceRows' as source_rows,
               output_summary ->> 'loadedRows' as loaded_rows,
               error_code, length(error_message) as message_length, error_key, error_args ->> 0 as first_arg
        from batch.pipeline_step_run where id = ?
        """;
    Map<String, Object> step = jdbcTemplate.queryForMap(stepResultSql, stepRunId);
    assertThat(step)
        .containsEntry("step_status", "FAILED")
        .containsEntry("run_seq", 1)
        .containsEntry("source_rows", "2")
        .containsEntry("loaded_rows", "1")
        .containsEntry("error_code", "LOAD_FAILED")
        .containsEntry("message_length", 1024)
        .containsEntry("error_key", "error.load.failed")
        .containsEntry("first_arg", "amount");
    String errorResultSql = """
        select pipeline_instance_id, pipeline_step_run_id, is_skipped,
               length(error_message) as message_length, raw_record ->> 'amount' as amount,
               error_key, error_args, source_row_num, source_column
        from batch.file_error_record where id = ?
        """;
    Map<String, Object> persistedError = jdbcTemplate.queryForMap(errorResultSql, errorId);
    assertThat(persistedError)
        .containsEntry("pipeline_instance_id", pipelineId)
        .containsEntry("pipeline_step_run_id", stepRunId)
        .containsEntry("is_skipped", true)
        .containsEntry("message_length", 1024)
        .containsEntry("amount", "invalid")
        .containsEntry("error_key", null)
        .containsEntry("error_args", null)
        .containsEntry("source_row_num", 3L)
        .containsEntry("source_column", "amount");
    String auditResultSql = """
        select count(*) from batch.file_audit_log
        where tenant_id = 't1' and file_id = 10 and operator_type = 'SYSTEM'
          and detail_summary ->> 'rows' = '2'
        """;
    assertThat(jdbcTemplate.queryForObject(auditResultSql, Integer.class)).isEqualTo(1);
  }

  private void insertRun(long instanceId, String stepCode, int runSeq, String status) {
    final String insertBatchPipelineStepRunSqlValue14 = """
        insert into batch.pipeline_step_run (pipeline_instance_id, step_code, stage_code, run_seq, step_status)\s\
        values (?, ?, ?, ?, ?)\
        """;
    jdbcTemplate.update(
        insertBatchPipelineStepRunSqlValue14, instanceId, stepCode, "COMPUTE", runSeq, status);
  }

  private List<String> succeededStepCodes(long instanceId) {
    try (SqlSession session = sqlSessionFactory.openSession()) {
      return session
          .getMapper(PlatformFileRuntimeMapper.class)
          .selectSucceededStepCodes(instanceId);
    }
  }

  @Test
  @DisplayName("(a) 最新一次为 SUCCESS → 返回")
  void latestSuccess_isReturned() {
    // arrange
    insertRun(100L, "COMPUTE", 1, "SUCCESS");

    // act + assert
    assertThat(succeededStepCodes(100L)).containsExactly("COMPUTE");
  }

  @Test
  @DisplayName("(b) SUCCESS 后又 FAILED → 不返回(防回归核心反例)")
  void successThenFailed_isNotReturned() {
    // arrange:曾成功(run_seq=1)后重跑失败(run_seq=2)
    insertRun(200L, "COMPUTE", 1, "SUCCESS");
    insertRun(200L, "COMPUTE", 2, "FAILED");

    // act + assert:旧 distinct-SUCCESS 会误返回 COMPUTE;修复后最新为 FAILED 不可跳过
    assertThat(succeededStepCodes(200L)).isEmpty();
  }

  @Test
  @DisplayName("(c) FAILED 后重跑 SUCCESS → 返回")
  void failedThenSuccess_isReturned() {
    // arrange
    insertRun(300L, "COMPUTE", 1, "FAILED");
    insertRun(300L, "COMPUTE", 2, "SUCCESS");

    // act + assert
    assertThat(succeededStepCodes(300L)).containsExactly("COMPUTE");
  }

  @Test
  @DisplayName("(d) 多 step 混合:各按各自最新终态判定")
  void multipleSteps_eachJudgedByOwnLatest() {
    // arrange
    // COMPUTE:最新 SUCCESS → 应含
    insertRun(400L, "COMPUTE", 1, "SUCCESS");
    // VALIDATE:SUCCESS→FAILED → 不含
    insertRun(400L, "VALIDATE", 1, "SUCCESS");
    insertRun(400L, "VALIDATE", 2, "FAILED");
    // TRANSFORM:FAILED→SUCCESS → 应含
    insertRun(400L, "TRANSFORM", 1, "FAILED");
    insertRun(400L, "TRANSFORM", 2, "SUCCESS");
    // COMMIT:最新 RUNNING(未落终态) → 不含
    insertRun(400L, "COMMIT", 1, "RUNNING");

    // act + assert
    assertThat(succeededStepCodes(400L)).containsExactlyInAnyOrder("COMPUTE", "TRANSFORM");
  }

  @Test
  @DisplayName("(e) 按 pipeline_instance_id 隔离,不串其他实例")
  void shouldIsolateByPipelineInstance_whenJudgingLatestStepStatus() {
    // arrange
    insertRun(500L, "COMPUTE", 1, "SUCCESS");
    insertRun(501L, "COMPUTE", 1, "FAILED");

    // act + assert
    assertThat(succeededStepCodes(500L)).containsExactly("COMPUTE");
    assertThat(succeededStepCodes(501L)).isEmpty();
  }

  @Test
  @DisplayName("锁等待结束后读取新快照，分配下一个 step run 序号")
  void concurrentStepRunAllocation_usesFreshSnapshotAfterLockWait() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    CountDownLatch contenderStarted = new CountDownLatch(1);
    try (SqlSession firstSession = sqlSessionFactory.openSession(false)) {
      PlatformFileRuntimeMapper firstMapper =
          firstSession.getMapper(PlatformFileRuntimeMapper.class);
      PlatformStepRunInsertParam firstParams = stepRunParams(900L);
      firstMapper.lockStepRunSequence(
          firstParams.getPipelineInstanceId(), firstParams.getStepCode());
      insertStepRun(firstMapper, firstParams, 1);

      Future<Integer> contender = executor.submit(() -> {
        try (SqlSession secondSession = sqlSessionFactory.openSession(false)) {
          final String selectSql = """
              select set_config('application_name', 'step-run-seq-contender', false)
          """;
          secondSession.getConnection().createStatement().execute(selectSql);
          PlatformFileRuntimeMapper secondMapper =
              secondSession.getMapper(PlatformFileRuntimeMapper.class);
          PlatformStepRunInsertParam secondParams = stepRunParams(900L);
          contenderStarted.countDown();
          secondMapper.lockStepRunSequence(
              secondParams.getPipelineInstanceId(), secondParams.getStepCode());
          int nextRunSeq = secondMapper.selectNextStepRunSeq(
              secondParams.getPipelineInstanceId(), secondParams.getStepCode());
          insertStepRun(secondMapper, secondParams, nextRunSeq);
          secondSession.commit();
          return nextRunSeq;
        }
      });

      assertThat(contenderStarted.await(5, TimeUnit.SECONDS)).isTrue();
      awaitAdvisoryLockWait();
      firstSession.commit();

      assertThat(contender.get(10, TimeUnit.SECONDS)).isEqualTo(2);
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    final String selectBatchPipelineStepRunSql = """
        select run_seq from batch.pipeline_step_run where pipeline_instance_id = 900 and step_code = 'COMPUTE'\s\
        order by run_seq\
        """;
    List<Integer> selectedRunSeqValues =
        jdbcTemplate.queryForList(selectBatchPipelineStepRunSql, Integer.class);
    assertThat(selectedRunSeqValues).containsExactly(1, 2);
  }

  private void awaitAdvisoryLockWait() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      final String selectPgStatActivitySql = """
          select count(*) from pg_stat_activity where application_name = 'step-run-seq-contender'\s\
          and wait_event_type = 'Lock'\
          """;
      Integer waiting = jdbcTemplate.queryForObject(selectPgStatActivitySql, Integer.class);
      if (waiting != null && waiting > 0) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("contender did not block on the advisory transaction lock");
  }

  private PlatformStepRunInsertParam stepRunParams(long pipelineInstanceId) {
    return PlatformStepRunInsertParam.builder()
        .pipelineInstanceId(pipelineInstanceId)
        .stepCode("COMPUTE")
        .stageCode("COMPUTE")
        .stepStatus("RUNNING")
        .inputSummaryJson("{}")
        .build();
  }

  private void insertStepRun(
      PlatformFileRuntimeMapper mapper, PlatformStepRunInsertParam params, int runSeq) {
    params.setRunSeq(runSeq);
    mapper.insertStepRun(params);
  }

  @Test
  @DisplayName("LOADED 成功回写清理旧失败元数据但保留本次统计")
  void loadedStatus_clearsStaleFailureMetadata() {
    final String insertBatchFileRecordSqlValue15 = """
        insert into batch.file_record (id, file_status, metadata_json, updated_at)
        values (700, 'FAILED', ?::jsonb, current_timestamp)
        """;
    jdbcTemplate.update(insertBatchFileRecordSqlValue15, """
        {
          "errorCode": "IMPORT_PARSE_FAILED",
          "errorMessage": "ClosedChannelException",
          "errorKey": "error.import.parse.failed",
          "errorArgs": "[]",
          "sourceBytes": 74177868
        }
        """.stripTrailing());

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      session
          .getMapper(PlatformFileRuntimeMapper.class)
          .updateFileRecordStatus(700L, FileStatus.LOADED.code(), "{\"loadedCount\":800000}");
    }

    final String selectBatchFileRecordSql = """
        select metadata_json from batch.file_record where id = 700
    """;
    Map<String, Object> metadata =
        jdbcTemplate.queryForObject(selectBatchFileRecordSql, (rs, rowNum) -> {
          try {
            return new ObjectMapper()
                .readValue(rs.getString(1), new TypeReference<Map<String, Object>>() {});
          } catch (Exception exception) {
            throw new IllegalStateException(exception);
          }
        });
    assertThat(metadata)
        .containsEntry(PipelineRuntimeKeys.IMPORT_LOADED_COUNT, 800000)
        .containsEntry("sourceBytes", 74177868)
        .doesNotContainKeys("errorCode", "errorMessage", "errorKey", "errorArgs");
  }
}
