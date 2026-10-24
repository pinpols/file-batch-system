package io.github.pinpols.batch.worker.dispatchs.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.io.InputStream;
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

@DisplayName("分发记录 Mapper:数据库拒绝跨租户文件或 Pipeline 引用")
class FileDispatchMapperTenantReferenceIntegrationTest {

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
    jdbcTemplate.execute("create table batch.file_record(id bigint, tenant_id varchar(64))");
    jdbcTemplate.execute("create table batch.pipeline_instance(id bigint, tenant_id varchar(64))");
    jdbcTemplate.execute("""
        create table batch.file_dispatch_record (
          tenant_id varchar(64), file_id bigint, pipeline_instance_id bigint,
          channel_code varchar(128), dispatch_target varchar(256), dispatch_status varchar(32),
          dispatch_attempt integer, receipt_code varchar(128), receipt_status varchar(32),
          external_request_id varchar(128)
        )
        """);
    Configuration configuration =
        new Configuration(new Environment("it", new JdbcTransactionFactory(), dataSource));
    try (InputStream xml = Resources.getResourceAsStream("mapper/FileDispatchMapper.xml")) {
      new XMLMapperBuilder(
              xml,
              configuration,
              "mapper/FileDispatchMapper.xml",
              configuration.getSqlFragments())
          .parse();
    }
    sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
  }

  @Test
  @DisplayName("仅创建同租户 file_record 与 pipeline_instance 的关联记录")
  void insertDispatchRecord_enforcesTenantOwnershipForBothReferences() {
    jdbcTemplate.update(
        "insert into batch.file_record(id, tenant_id) values (10, 't1'), (20, 't2')");
    jdbcTemplate.update(
        "insert into batch.pipeline_instance(id, tenant_id) values (100, 't1'), (200, 't2')");

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      FileDispatchMapper mapper = session.getMapper(FileDispatchMapper.class);
      assertThat(mapper.insertDispatchRecord(params("t1", 10L, 100L))).isEqualTo(1);
      assertThat(mapper.insertDispatchRecord(params("t1", 20L, 100L))).isZero();
      assertThat(mapper.insertDispatchRecord(params("t1", 10L, 200L))).isZero();
    }
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.file_dispatch_record", Integer.class))
        .isEqualTo(1);
  }

  private static Map<String, Object> params(String tenantId, Long fileId, Long pipelineInstanceId) {
    return Map.of(
        "tenantId", tenantId,
        "fileId", fileId,
        "pipelineInstanceId", pipelineInstanceId,
        "channelCode", "CH_TEST",
        "dispatchTarget", "target",
        "dispatchStatus", "CREATED",
        "receiptCode", "receipt",
        "receiptStatus", "NONE",
        "externalRequestId", "request");
  }
}
