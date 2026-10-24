package io.github.pinpols.batch.worker.core.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
@DisplayName("selectSucceededStepCodes:每个 step 最新一次 run 为 SUCCESS 才可跳过")
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

    Map<String, Object> own = Map.of("tenantId", "t1", "pipelineInstanceId", 1L, "fileId", 10L);
    Map<String, Object> foreignFile =
        Map.of("tenantId", "t1", "pipelineInstanceId", 1L, "fileId", 20L);
    Map<String, Object> foreignPipeline =
        Map.of("tenantId", "t1", "pipelineInstanceId", 2L, "fileId", 10L);

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      PlatformFileRuntimeMapper mapper = session.getMapper(PlatformFileRuntimeMapper.class);
      assertThat(mapper.bindFileToPipelineInstance(own)).isEqualTo(1);
      assertThat(mapper.bindFileToPipelineInstance(foreignFile)).isZero();
      assertThat(mapper.bindFileToPipelineInstance(foreignPipeline)).isZero();
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

  private static Map<String, Object> pipelineParams(Long fileId, Long jobInstanceId) {
    Map<String, Object> params = new HashMap<>();
    params.put("tenantId", "t1");
    params.put("systemPipelineCode", "IMPORT");
    params.put("systemPipelineVersion", 1);
    params.put("jobCode", "JOB_TEST");
    params.put("pipelineType", "IMPORT");
    params.put("fileId", fileId);
    params.put("relatedJobInstanceId", jobInstanceId);
    params.put("currentStage", "RECEIVE");
    params.put("runStatus", "RUNNING");
    params.put("traceId", "trace-test");
    return params;
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
    Map<String, Object> error = new HashMap<>(Map.of(
        "tenantId",
        "t1",
        "fileId",
        11L,
        "pipelineInstanceId",
        101L,
        "pipelineStepRunId",
        301L,
        "recordNo",
        1L,
        "isSkipped",
        false));
    Map<String, Object> audit = new HashMap<>(Map.of(
        "tenantId",
        "t1",
        "fileId",
        11L,
        "operationType",
        "TEST",
        "operationResult",
        "SUCCESS",
        "operatorType",
        "SYSTEM"));

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      PlatformFileRuntimeMapper mapper = session.getMapper(PlatformFileRuntimeMapper.class);
      assertThat(mapper.insertFileErrorRecord(error)).isEqualTo(1);
      error.put("fileId", 21L);
      assertThat(mapper.insertFileErrorRecord(error)).isZero();
      error.put("fileId", 11L);
      error.put("pipelineInstanceId", 201L);
      error.put("pipelineStepRunId", 401L);
      assertThat(mapper.insertFileErrorRecord(error)).isZero();
      assertThat(mapper.insertFileAuditLog(audit)).isEqualTo(1);
      audit.put("fileId", 21L);
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
    Map<String, Object> audit = new HashMap<>(Map.of(
        "tenantId", "t1",
        "fileId", 31L,
        "operationType", "DISPATCH_COMPLETE",
        "operationResult", "SUCCESS",
        "operatorType", "SYSTEM",
        "evidenceRef", "external-request-1",
        "detailSummaryJson", "{\"channelCode\":\"channel-a\"}"));

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      PlatformFileRuntimeMapper mapper = session.getMapper(PlatformFileRuntimeMapper.class);
      assertThat(mapper.insertFileAuditLog(audit)).isEqualTo(1);
      assertThat(mapper.insertFileAuditLog(audit)).isZero();
      audit.put("detailSummaryJson", "{\"channelCode\":\"channel-b\"}");
      assertThat(mapper.insertFileAuditLog(audit)).isEqualTo(1);
      audit.put("detailSummaryJson", "{\"channelCode\":\"channel-a\"}");
      audit.put("evidenceRef", "external-request-2");
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
    Map<String, Object> error = new HashMap<>();
    error.put("tenantId", "t1");
    error.put("fileId", null);
    error.put("pipelineInstanceId", null);
    error.put("pipelineStepRunId", null);
    error.put("recordNo", 1L);
    error.put("isSkipped", false);

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

  private void insertRun(long instanceId, String stepCode, int runSeq, String status) {
    final String insertBatchPipelineStepRunSqlValue14 = """
        insert into batch.pipeline_step_run (pipeline_instance_id, step_code, stage_code, run_seq, step_status)\s\
        values (?, ?, ?, ?, ?)\
        """;
    jdbcTemplate.update(
        insertBatchPipelineStepRunSqlValue14, instanceId, stepCode, "COMPUTE", runSeq, status);
  }

  private List<String> succeededStepCodes(long instanceId) {
    Map<String, Object> params = new HashMap<>();
    params.put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, instanceId);
    try (SqlSession session = sqlSessionFactory.openSession()) {
      return session.getMapper(PlatformFileRuntimeMapper.class).selectSucceededStepCodes(params);
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
      Map<String, Object> firstParams = stepRunParams(900L);
      firstMapper.lockStepRunSequence(firstParams);
      insertStepRun(firstMapper, firstParams, 1);

      Future<Integer> contender = executor.submit(() -> {
        try (SqlSession secondSession = sqlSessionFactory.openSession(false)) {
          final String selectSql = """
              select set_config('application_name', 'step-run-seq-contender', false)
          """;
          secondSession.getConnection().createStatement().execute(selectSql);
          PlatformFileRuntimeMapper secondMapper =
              secondSession.getMapper(PlatformFileRuntimeMapper.class);
          Map<String, Object> secondParams = stepRunParams(900L);
          contenderStarted.countDown();
          secondMapper.lockStepRunSequence(secondParams);
          int nextRunSeq = secondMapper.selectNextStepRunSeq(secondParams);
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

  private Map<String, Object> stepRunParams(long pipelineInstanceId) {
    Map<String, Object> params = new HashMap<>();
    params.put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, pipelineInstanceId);
    params.put("stepCode", "COMPUTE");
    params.put("stageCode", "COMPUTE");
    params.put("stepStatus", "RUNNING");
    params.put("inputSummaryJson", "{}");
    return params;
  }

  private void insertStepRun(
      PlatformFileRuntimeMapper mapper, Map<String, Object> params, int runSeq) {
    params.put("runSeq", runSeq);
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

    Map<String, Object> params = new HashMap<>();
    params.put(PipelineRuntimeKeys.FILE_ID, 700L);
    params.put("fileStatus", FileStatus.LOADED.code());
    params.put("metadataJson", "{\"loadedCount\":800000}");
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      session.getMapper(PlatformFileRuntimeMapper.class).updateFileRecordStatus(params);
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
