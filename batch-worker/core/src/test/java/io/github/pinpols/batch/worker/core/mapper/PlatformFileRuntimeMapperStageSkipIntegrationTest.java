package io.github.pinpols.batch.worker.core.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.TestContainerImages;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * 加载,非手抄 SQL)钉死修复后的语义。
 *
 * <p>不走 {@code AbstractIntegrationTest}/Spring:worker-core 是库模块无 Spring Boot 启动类,照姊妹 {@code
 * ProcessStageSkipCrashResumeIntegrationTest} 的裸 PG + JdbcTemplate 惯例,只是额外用 MyBatis
 * SqlSessionFactory 直加载真 XML,以覆盖 SQL 真伪(窗口函数、tie-break、分区取最新)。
 */
@DisplayName("selectSucceededStepCodes:每个 step 最新一次 run 为 SUCCESS 才可跳过")
class PlatformFileRuntimeMapperStageSkipIntegrationTest {

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(TestContainerImages.POSTGRES);

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
    DataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);

    jdbcTemplate.execute("drop schema if exists batch cascade");
    jdbcTemplate.execute("create schema batch");
    // 与 V6 pipeline_step_run 列/约束对齐(去掉对 pipeline_instance 的 FK 以自成一体,SELECT 不触父表)。
    jdbcTemplate.execute(
        """
        create table batch.pipeline_step_run (
          id                   bigserial primary key,
          pipeline_instance_id bigint       not null,
          step_code            varchar(128) not null,
          stage_code           varchar(64)  not null,
          run_seq              integer      not null default 1,
          step_status          varchar(32)  not null,
          started_at           timestamptz,
          finished_at          timestamptz,
          constraint uk_pipeline_step_run unique (pipeline_instance_id, step_code, run_seq)
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
}
