package io.github.pinpols.batch.worker.core.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.TestContainerImages;
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
import org.testcontainers.utility.DockerImageName;

/**
 * ADR-038 P0 mapper IT:真 PG 上验证 {@link PipelineProgressMapper#deleteByInstance} 的 SQL 形状 —— 补偿删业务行后
 * 作废该 pipeline 实例的**全部 stage 位点**,且严格 scope 在 (tenant, instance),不误删他实例 / 他租户。
 *
 * <p>不起 Spring / 不跑 Flyway:手工建最小 {@code batch.pipeline_progress} 表(V164 相关列)+ 挂 mapper XML,聚焦本次新增
 * delete 的正确性;advance/markCompleted 的语义已由 {@code DefaultProcessingPositionStoreTest} + 崩溃恢复 IT 覆盖。
 */
@Testcontainers(disabledWithoutDocker = true)
class PipelineProgressMapperIntegrationTest {

  @Container
  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse(TestContainerImages.POSTGRES))
          .withDatabaseName("batch_ckpt_it")
          .withUsername("batch_user")
          .withPassword("batch_pass_123");

  private static SingleConnectionDataSource dataSource;
  private static SqlSessionFactory sqlSessionFactory;
  private static JdbcTemplate jdbc;

  @BeforeAll
  static void setUp() throws Exception {
    dataSource =
        new SingleConnectionDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("CREATE SCHEMA IF NOT EXISTS batch");
    jdbc.execute(
        """
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
        """);
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
    jdbc.execute("TRUNCATE batch.pipeline_progress RESTART IDENTITY");
  }

  @Test
  @DisplayName("deleteByInstance:删该实例全部 stage 位点,不触碰他实例 / 他租户")
  void deleteByInstance_removesAllStagesForInstanceOnly() {
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      PipelineProgressMapper mapper = session.getMapper(PipelineProgressMapper.class);
      // 目标实例 100:两个 stage 位点(LOAD + GENERATE)
      mapper.advance("ta", 100L, "LOAD", "row:5", 5L);
      mapper.advance("ta", 100L, "GENERATE", "S|c1", 3L);
      // 同租户他实例 101、他租户 tb 同实例号 100:都不应被删
      mapper.advance("ta", 101L, "LOAD", "row:9", 9L);
      mapper.advance("tb", 100L, "LOAD", "row:1", 1L);

      int deleted = mapper.deleteByInstance("ta", 100L);

      assertThat(deleted).isEqualTo(2);
      assertThat(mapper.findByInstanceAndStage("ta", 100L, "LOAD")).isNull();
      assertThat(mapper.findByInstanceAndStage("ta", 100L, "GENERATE")).isNull();
      // 他实例 / 他租户位点原样保留
      assertThat(mapper.findByInstanceAndStage("ta", 101L, "LOAD")).isNotNull();
      assertThat(mapper.findByInstanceAndStage("tb", 100L, "LOAD")).isNotNull();
      Integer remaining =
          jdbc.queryForObject("SELECT count(*) FROM batch.pipeline_progress", Integer.class);
      assertThat(remaining).isEqualTo(2);
    }
  }

  @Test
  @DisplayName("deleteByInstance:含 completed 位点也一并清(补偿后重试须从头全量重做)")
  void deleteByInstance_clearsCompletedPositionsToo() {
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      PipelineProgressMapper mapper = session.getMapper(PipelineProgressMapper.class);
      mapper.advance("ta", 200L, "LOAD", "row:10", 10L);
      mapper.markCompleted("ta", 200L, "LOAD");
      assertThat(mapper.findByInstanceAndStage("ta", 200L, "LOAD").completed()).isTrue();

      int deleted = mapper.deleteByInstance("ta", 200L);

      assertThat(deleted).isEqualTo(1);
      assertThat(mapper.findByInstanceAndStage("ta", 200L, "LOAD")).isNull();
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
  void mapperStatementsResolve() {
    List<String> ids =
        List.of("findByInstanceAndStage", "advance", "markCompleted", "deleteByInstance");
    for (String id : ids) {
      assertThat(
              sqlSessionFactory
                  .getConfiguration()
                  .hasStatement(PipelineProgressMapper.class.getName() + "." + id))
          .as(id)
          .isTrue();
    }
  }
}
