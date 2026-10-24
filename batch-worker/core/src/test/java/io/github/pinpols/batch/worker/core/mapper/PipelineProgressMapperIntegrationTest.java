package io.github.pinpols.batch.worker.core.mapper;

import io.github.pinpols.batch.worker.core.param.PipelineProgressAdvanceParam;


import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.io.InputStream;
import java.util.List;
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
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ADR-038 P0 mapper IT:真 PG 上验证 {@link PipelineProgressMapper#deleteByInstance} 的 SQL 形状 —— 补偿删业务行后
 * 作废该 pipeline 实例的**全部 stage 位点**,且严格 scope 在 (tenant, instance),不误删他实例 / 他租户。
 *
 * <p>不起 Spring / 不跑 Flyway:手工建最小 {@code batch.pipeline_progress} 表(V164 相关列)+ 挂 mapper XML,聚焦本次新增
 * delete 的正确性;advance/markCompleted 的语义已由 {@code DefaultProcessingPositionStoreTest} + 崩溃恢复 IT 覆盖。
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("流水线进度映射器: 按实例删除位点的范围隔离与幂等性")
class PipelineProgressMapperIntegrationTest {

  @Container
  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      TestPostgresContainers.create("batch_ckpt_it");

  private static SingleConnectionDataSource dataSource;
  private static SqlSessionFactory sqlSessionFactory;
  private static JdbcTemplate jdbc;

  @BeforeAll
  static void setUp() throws Exception {
    dataSource = new SingleConnectionDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    jdbc = new JdbcTemplate(dataSource);
    final String createSql = """
        CREATE SCHEMA IF NOT EXISTS batch
    """;
    jdbc.execute(createSql);
    final String createBatchPipelineInstanceSql = """
        CREATE TABLE batch.pipeline_instance (
          id BIGINT PRIMARY KEY,
          tenant_id VARCHAR(64) NOT NULL
        )
        """;
    jdbc.execute(createBatchPipelineInstanceSql);
    final String createBatchPipelineProgressSql = """
        CREATE TABLE batch.pipeline_progress (
          id                   BIGSERIAL   PRIMARY KEY,
          tenant_id            VARCHAR(64) NOT NULL,
          pipeline_instance_id BIGINT      NOT NULL,
          stage                VARCHAR(32) NOT NULL,
          position_marker      VARCHAR(512),
          processed_count      BIGINT      NOT NULL DEFAULT 0,
          completed            BOOLEAN     NOT NULL DEFAULT FALSE,
          completed_at         TIMESTAMPTZ,
          created_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
          updated_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
          CONSTRAINT uk_pp UNIQUE (tenant_id, pipeline_instance_id, stage)
        )
        """;
    jdbc.execute(createBatchPipelineProgressSql);
    Configuration configuration =
        new Configuration(new Environment("it", new JdbcTransactionFactory(), dataSource));
    String resource = "mapper/PipelineProgressMapper.xml";
    try (InputStream in = Resources.getResourceAsStream(resource)) {
      new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
    }
    sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
  }

  @AfterAll
  static void tearDown() {
    if (dataSource != null) {
      dataSource.destroy();
    }
  }

  @BeforeEach
  void clean() {
    final String truncateSql = """
        TRUNCATE batch.pipeline_progress RESTART IDENTITY
    """;
    jdbc.execute(truncateSql);
    final String truncateSqlValue1 = """
        TRUNCATE batch.pipeline_instance
    """;
    jdbc.execute(truncateSqlValue1);
  }

  @Test
  @DisplayName("deleteByInstance:删该实例全部 stage 位点,不触碰他实例 / 他租户")
  void deleteByInstance_removesAllStagesForInstanceOnly() {
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      PipelineProgressMapper mapper = session.getMapper(PipelineProgressMapper.class);
      insertInstance("ta", 100L);
      insertInstance("ta", 101L);
      insertInstance("tb", 200L);
      // 目标实例 100:两个 stage 位点(LOAD + GENERATE)
      mapper.advance(PipelineProgressAdvanceParam.builder()
          .tenantId("ta")
          .pipelineInstanceId(100L)
          .stage("LOAD")
          .positionMarker("row:5")
          .processedCountIncrement(5L)
          .build());
      mapper.advance(PipelineProgressAdvanceParam.builder()
          .tenantId("ta")
          .pipelineInstanceId(100L)
          .stage("GENERATE")
          .positionMarker("S|c1")
          .processedCountIncrement(3L)
          .build());
      // 同租户他实例 101、他租户 tb 同实例号 100:都不应被删
      mapper.advance(PipelineProgressAdvanceParam.builder()
          .tenantId("ta")
          .pipelineInstanceId(101L)
          .stage("LOAD")
          .positionMarker("row:9")
          .processedCountIncrement(9L)
          .build());
      mapper.advance(PipelineProgressAdvanceParam.builder()
          .tenantId("tb")
          .pipelineInstanceId(200L)
          .stage("LOAD")
          .positionMarker("row:1")
          .processedCountIncrement(1L)
          .build());

      int deleted = mapper.deleteByInstance("ta", 100L);

      assertThat(deleted).isEqualTo(2);
      assertThat(mapper.findByInstanceAndStage("ta", 100L, "LOAD")).isNull();
      assertThat(mapper.findByInstanceAndStage("ta", 100L, "GENERATE")).isNull();
      // 他实例 / 他租户位点原样保留
      assertThat(mapper.findByInstanceAndStage("ta", 101L, "LOAD")).isNotNull();
      assertThat(mapper.findByInstanceAndStage("tb", 200L, "LOAD")).isNotNull();
      final String selectBatchPipelineProgressSql = """
          SELECT count(*) FROM batch.pipeline_progress
      """;
      Integer remaining = jdbc.queryForObject(selectBatchPipelineProgressSql, Integer.class);
      assertThat(remaining).isEqualTo(2);
    }
  }

  @Test
  @DisplayName("deleteByInstance:含 completed 位点也一并清(补偿后重试须从头全量重做)")
  void deleteByInstance_clearsCompletedPositionsToo() {
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      PipelineProgressMapper mapper = session.getMapper(PipelineProgressMapper.class);
      insertInstance("ta", 200L);
      mapper.advance(PipelineProgressAdvanceParam.builder()
          .tenantId("ta")
          .pipelineInstanceId(200L)
          .stage("LOAD")
          .positionMarker("row:10")
          .processedCountIncrement(10L)
          .build());
      mapper.markCompleted("ta", 200L, "LOAD");
      assertThat(mapper.findByInstanceAndStage("ta", 200L, "LOAD").completed()).isTrue();

      int deleted = mapper.deleteByInstance("ta", 200L);

      assertThat(deleted).isEqualTo(1);
      assertThat(mapper.findByInstanceAndStage("ta", 200L, "LOAD")).isNull();
    }
  }

  @Test
  @DisplayName("advance 与 markCompleted 拒绝不属于调用租户的 Pipeline 实例")
  void shouldRejectWrites_whenPipelineInstanceOwnedByAnotherTenant() {
    insertInstance("tb", 300L);
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      PipelineProgressMapper mapper = session.getMapper(PipelineProgressMapper.class);
      assertThat(mapper.advance(PipelineProgressAdvanceParam.builder()
          .tenantId("ta")
          .pipelineInstanceId(300L)
          .stage("LOAD")
          .positionMarker("row:1")
          .processedCountIncrement(1L)
          .build())).isZero();
      assertThat(mapper.markCompleted("ta", 300L, "LOAD")).isZero();
      assertThat(mapper.advance(PipelineProgressAdvanceParam.builder()
          .tenantId("tb")
          .pipelineInstanceId(300L)
          .stage("LOAD")
          .positionMarker("row:1")
          .processedCountIncrement(1L)
          .build())).isEqualTo(1);
      assertThat(mapper.markCompleted("tb", 300L, "LOAD")).isEqualTo(1);
      assertThat(mapper.findByInstanceAndStage("ta", 300L, "LOAD")).isNull();
      assertThat(mapper.findByInstanceAndStage("tb", 300L, "LOAD").completed()).isTrue();
    }
  }

  @Test
  @DisplayName("deleteByInstance:无位点时删 0 行(幂等,重复补偿安全)")
  void deleteByInstance_isIdempotentWhenNoRows() {
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      PipelineProgressMapper mapper = session.getMapper(PipelineProgressMapper.class);
      assertThat(mapper.deleteByInstance("ta", 999L)).isZero();
    }
  }

  @Test
  @DisplayName("mapper XML 与列定义可解析装配(select/insert/delete 全绑定)")
  void shouldResolveAllMapperStatements_whenXmlIsParsed() {
    List<String> ids =
        List.of("findByInstanceAndStage", "advance", "markCompleted", "deleteByInstance");
    for (String id : ids) {
      assertThat(sqlSessionFactory
              .getConfiguration()
              .hasStatement(PipelineProgressMapper.class.getName() + "." + id))
          .as(id)
          .isTrue();
    }
  }

  private void insertInstance(String tenantId, Long id) {
    final String insertBatchPipelineInstanceSql = """
        INSERT INTO batch.pipeline_instance(id, tenant_id) VALUES (?, ?)
    """;
    jdbc.update(insertBatchPipelineInstanceSql, id, tenantId);
  }
}
