package io.github.pinpols.batch.worker.core.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.TestPostgresContainers;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileTemplateRepository;
import java.io.InputStream;
import java.time.Instant;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 真实驱动与 MyBatis 映射共同守护模板固定投影到插件动态参数的边界。 */
@DisplayName("Worker模板映射：固定投影经插件边界转换为动态配置")
class PlatformFileTemplateMappingIntegrationTest {

  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.create();
  private static SqlSessionFactory sessionFactory;

  @BeforeAll
  static void startDatabase() throws Exception {
    POSTGRES.start();
    DataSource dataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    String createSchemaSql = "create schema batch";
    jdbcTemplate.execute(createSchemaSql);
    // 只验证读取投影，不模拟生产迁移；字段类型与模板库的读取契约保持独立预期。
    String createTemplateSql = """
        create table batch.file_template_config as select
          11::bigint as id, 'tenant-a'::varchar as tenant_id,
          'TEMPLATE_A'::varchar as template_code, '模板A'::varchar as template_name,
          'EXPORT'::varchar as template_type, 'REPORT'::varchar as biz_type,
          'DELIMITED'::varchar as file_format_type, 'UTF-8'::varchar as charset,
          null::varchar as target_charset, false as with_bom, 'LF'::varchar as line_separator,
          ','::varchar as delimiter, '"'::varchar as quote_char, null::varchar as escape_char,
          0::integer as record_length, 0::integer as header_rows, 0::integer as footer_rows,
          null::jsonb as header_template, null::jsonb as trailer_template,
          'SHA-256'::varchar as checksum_type, 'NONE'::varchar as compress_type,
          'NONE'::varchar as encrypt_type, 'report.csv'::varchar as naming_rule,
          '[{"field":"amount"}]'::jsonb as field_mappings, '{}'::jsonb as validation_rule_set,
          null::varchar as default_query_code, 'select amount from report'::text as default_query_sql,
          '{}'::jsonb as query_param_schema, true as streaming_enabled,
          101::integer as page_size, 102::integer as fetch_size, 103::integer as chunk_size,
          true as enabled, 1::integer as version, null::varchar as description,
          null::varchar as created_by, null::varchar as updated_by,
          '2026-10-01T02:03:04Z'::timestamptz as created_at,
          '2026-10-02T02:03:04Z'::timestamptz as updated_at,
          '[]'::jsonb as preprocess_pipeline, true as preview_masking_enabled,
          false as error_line_masking_enabled, true as log_masking_enabled,
          false as content_encryption_enabled, null::varchar as encryption_key_ref,
          true as download_requires_approval, '{}'::jsonb as masking_rule_set,
          '{"pluginId":"report"}'::jsonb as export_data_ref,
          null::jsonb as load_target_ref, false as is_deleted
        """;
    jdbcTemplate.execute(createTemplateSql);
    Configuration configuration =
        new Configuration(new Environment("mapping", new JdbcTransactionFactory(), dataSource));
    try (InputStream xml = Resources.getResourceAsStream("mapper/PlatformFileRuntimeMapper.xml")) {
      XMLMapperBuilder builder = new XMLMapperBuilder(
          xml,
          configuration,
          "mapper/PlatformFileRuntimeMapper.xml",
          configuration.getSqlFragments());
      builder.parse();
    }
    sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  @Test
  @DisplayName("真实XML映射保留JSONB、时间和可空字段，并由插件边界提供原有键名")
  void shouldLoadTemplateFromTypedMappingAndExposePluginValues() {
    try (SqlSession session = sessionFactory.openSession()) {
      PlatformFileRuntimeMapper mapper = session.getMapper(PlatformFileRuntimeMapper.class);
      PlatformFileTemplateRepository repository = new PlatformFileTemplateRepository(mapper);
      Map<String, Object> values =
          repository.loadLatestTemplateConfig("tenant-a", "TEMPLATE_A", "EXPORT");
      assertThat(values)
          .containsEntry("id", 11L)
          .containsEntry("template_name", "模板A")
          .containsEntry("page_size", 101)
          .containsEntry("fetch_size", 102)
          .containsEntry("chunk_size", 103)
          .containsEntry("preview_masking_enabled", true)
          .containsEntry("download_requires_approval", true)
          .containsEntry("target_charset", null)
          .containsEntry("created_at", Instant.parse("2026-10-01T02:03:04Z"));
      assertThat(values.get("field_mappings").toString()).contains("amount");
      assertThat(values.get("export_data_ref").toString()).contains("report");
    }
  }

  @Test
  @DisplayName("模板查询保留租户与类型隔离，缺失模板返回空配置")
  void shouldRejectOtherTenantAndTemplateType() {
    try (SqlSession session = sessionFactory.openSession()) {
      PlatformFileRuntimeMapper mapper = session.getMapper(PlatformFileRuntimeMapper.class);
      PlatformFileTemplateRepository repository = new PlatformFileTemplateRepository(mapper);
      Map<String, Object> foreignTenant =
          repository.loadLatestTemplateConfig("tenant-b", "TEMPLATE_A", "EXPORT");
      Map<String, Object> wrongType =
          repository.loadLatestTemplateConfig("tenant-a", "TEMPLATE_A", "IMPORT");
      assertThat(foreignTenant).isEmpty();
      assertThat(wrongType).isEmpty();
    }
  }
}
