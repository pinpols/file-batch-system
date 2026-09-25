package io.github.pinpols.batch.worker.core.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.testing.TestPostgresContainers;
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

    jdbcTemplate.execute("drop schema if exists batch cascade");
    jdbcTemplate.execute("create schema batch");
    // 与 V6 pipeline_step_run 列/约束对齐(去掉对 pipeline_instance 的 FK 以自成一体,SELECT 不触父表)。
    jdbcTemplate.execute("""
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
        """);
    jdbcTemplate.execute("""
        create table batch.file_record (
          id           bigint primary key,
          file_status  varchar(32) not null,
          metadata_json jsonb,
          updated_at   timestamptz
        )
        """);

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

  private void insertRun(long instanceId, String stepCode, int runSeq, String status) {
    jdbcTemplate.update(
        "insert into batch.pipeline_step_run "
            + "(pipeline_instance_id, step_code, stage_code, run_seq, step_status) "
            + "values (?, ?, ?, ?, ?)",
        instanceId,
        stepCode,
        "COMPUTE",
        runSeq,
        status);
  }

  private List<String> succeededStepCodes(long instanceId) {
    Map<String, Object> params = new HashMap<>();
    params.put("pipelineInstanceId", instanceId);
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
  void isolatedByPipelineInstanceId() {
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
          secondSession
              .getConnection()
              .createStatement()
              .execute("select set_config('application_name', 'step-run-seq-contender', false)");
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

    assertThat(jdbcTemplate.queryForList(
            "select run_seq from batch.pipeline_step_run "
                + "where pipeline_instance_id = 900 and step_code = 'COMPUTE' order by run_seq",
            Integer.class))
        .containsExactly(1, 2);
  }

  private void awaitAdvisoryLockWait() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      Integer waiting = jdbcTemplate.queryForObject(
          "select count(*) from pg_stat_activity "
              + "where application_name = 'step-run-seq-contender' "
              + "and wait_event_type = 'Lock'",
          Integer.class);
      if (waiting != null && waiting > 0) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("contender did not block on the advisory transaction lock");
  }

  private Map<String, Object> stepRunParams(long pipelineInstanceId) {
    Map<String, Object> params = new HashMap<>();
    params.put("pipelineInstanceId", pipelineInstanceId);
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
    jdbcTemplate.update(
        "insert into batch.file_record (id, file_status, metadata_json, updated_at) "
            + "values (700, 'FAILED', ?::jsonb, current_timestamp)",
        "{\"errorCode\":\"IMPORT_PARSE_FAILED\","
            + "\"errorMessage\":\"ClosedChannelException\","
            + "\"errorKey\":\"error.import.parse.failed\","
            + "\"errorArgs\":\"[]\",\"sourceBytes\":74177868}");

    Map<String, Object> params = new HashMap<>();
    params.put("fileId", 700L);
    params.put("fileStatus", "LOADED");
    params.put("metadataJson", "{\"loadedCount\":800000}");
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      session.getMapper(PlatformFileRuntimeMapper.class).updateFileRecordStatus(params);
    }

    Map<String, Object> metadata = jdbcTemplate.queryForObject(
        "select metadata_json from batch.file_record where id = 700", (rs, rowNum) -> {
          try {
            return new ObjectMapper()
                .readValue(rs.getString(1), new TypeReference<Map<String, Object>>() {});
          } catch (Exception exception) {
            throw new IllegalStateException(exception);
          }
        });
    assertThat(metadata)
        .containsEntry("loadedCount", 800000)
        .containsEntry("sourceBytes", 74177868)
        .doesNotContainKeys("errorCode", "errorMessage", "errorKey", "errorArgs");
  }
}
