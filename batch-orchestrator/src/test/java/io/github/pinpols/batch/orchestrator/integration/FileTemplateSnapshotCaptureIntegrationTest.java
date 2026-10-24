package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.config.BatchKmsProperties;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.dto.FrozenFileTemplateSnapshot;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.mapper.FileTemplateSnapshotMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.service.FileTemplateSnapshotService;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Base64;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("文件模板捕获使用真实 PostgreSQL JSON 与平台认证加密")
class FileTemplateSnapshotCaptureIntegrationTest {

  @Container
  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.platform();

  @Test
  @DisplayName("完整迁移库捕获嵌套 JSON 和空值，更新模板后原密文仍为原版本且租户隔离")
  void shouldFreezeNativeJson_whenDatabaseTemplateChangesAfterCapture() throws Exception {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .schemas("batch", "quartz")
        .defaultSchema("batch")
        .locations("classpath:db/migration")
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        .load()
        .migrate();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String tenant = "template_capture_" + UUID.randomUUID().toString().replace("-", "");
    final String insertTemplateSql = """
        insert into batch.file_template_config
          (tenant_id, template_code, template_name, template_type, file_format_type, version,
           naming_rule, field_mappings, query_param_schema, header_template)
        values (?, 'FILE_JOB_TPL', 'captured template', ?, 'DELIMITED', ?, ?,
                '[{"source":"id","target":"record_id"}]'::jsonb,
                '{"export_data_ref":"EXPORT_QUERY_TEST","bindings":{"region":"frozen"}}'::jsonb,
                null)
        returning id
        """;
    Long templateId =
        jdbc.queryForObject(insertTemplateSql, Long.class, tenant, "EXPORT", 7, "before");
    jdbc.queryForObject(insertTemplateSql, Long.class, tenant + "_other", "EXPORT", 999, "other");
    jdbc.queryForObject(insertTemplateSql, Long.class, tenant, "IMPORT", 99, "wrong-type");
    final String updateTemplateSql = """
        update batch.file_template_config set version=8, naming_rule='after'
        where tenant_id=? and id=?
        """;
    Configuration configuration = new Configuration(
        new Environment("file-template-capture", new JdbcTransactionFactory(), dataSource));
    String mapperPath = "mapper/FileTemplateSnapshotMapper.xml";
    try (InputStream mapperXml = Resources.getResourceAsStream(mapperPath)) {
      XMLMapperBuilder mapperBuilder = new XMLMapperBuilder(
          mapperXml, configuration, mapperPath, configuration.getSqlFragments());
      mapperBuilder.parse();
    }
    SqlSessionFactory sessions = new SqlSessionFactoryBuilder().build(configuration);
    SecretPayloadProtector protector = protector();
    JobDefinitionEntity definition = JobDefinitionEntity.builder()
        .id(1L)
        .tenantId(tenant)
        .jobCode("FILE_JOB")
        .jobType("EXPORT")
        .build();
    LaunchRequest request =
        LaunchRequest.builder().tenantId(tenant).jobCode("FILE_JOB").build();
    String capturedCiphertext;
    try (SqlSession session = sessions.openSession()) {
      FileTemplateSnapshotMapper mapper = session.getMapper(FileTemplateSnapshotMapper.class);
      FileTemplateSnapshotService service =
          new FileTemplateSnapshotService(mapper, mock(JobInstanceMapper.class), protector);

      Map<String, Object> pinned = service.pin(request, definition, Map.of());

      capturedCiphertext = (String) pinned.get("frozenFileTemplateProtected");
      assertThat(capturedCiphertext).doesNotContain("EXPORT_QUERY_TEST", "frozen", "before");
      String plaintext = protector.revealEncrypted(capturedCiphertext);
      FrozenFileTemplateSnapshot captured =
          JsonUtils.fromJsonStrict(plaintext, FrozenFileTemplateSnapshot.class);
      assertThat(captured.templateId()).isEqualTo(templateId);
      assertThat(captured.templateVersion()).isEqualTo(7);
      assertThat(captured.configuration()).containsEntry("header_template", null);
      assertThat(captured.configuration().get("created_at")).isInstanceOf(String.class);
      assertThat(captured.configuration().get("field_mappings"))
          .isEqualTo(List.of(Map.of("source", "id", "target", "record_id")));
      assertThat(captured.configuration().get("query_param_schema"))
          .isEqualTo(Map.of(
              "export_data_ref", "EXPORT_QUERY_TEST", "bindings", Map.of("region", "frozen")));
      String otherTenant =
          mapper.selectLatestConfiguration(tenant + "_missing", "FILE_JOB_TPL", "EXPORT");
      assertThat(otherTenant).isNull();
    }
    jdbc.update(updateTemplateSql, tenant, templateId);
    try (SqlSession session = sessions.openSession()) {
      FileTemplateSnapshotMapper mapper = session.getMapper(FileTemplateSnapshotMapper.class);
      String latestJson = mapper.selectLatestConfiguration(tenant, "FILE_JOB_TPL", "EXPORT");
      Map<String, Object> latest =
          JsonUtils.fromJson(latestJson, new TypeReference<Map<String, Object>>() {});
      String originalJson = protector.revealEncrypted(capturedCiphertext);
      FrozenFileTemplateSnapshot original =
          JsonUtils.fromJsonStrict(originalJson, FrozenFileTemplateSnapshot.class);

      assertThat(latest).containsEntry("version", 8).containsEntry("naming_rule", "after");
      assertThat(original.templateVersion()).isEqualTo(7);
      assertThat(original.configuration()).containsEntry("naming_rule", "before");
    }
  }

  private SecretPayloadProtector protector() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    String encodedKey = Base64.getEncoder().encodeToString(key);
    BatchKmsProperties kms = new BatchKmsProperties();
    kms.setDefaultKeyRef("capture-test");
    kms.setKeys(Map.of("capture-test", encodedKey));
    BatchSecurityProperties security = new BatchSecurityProperties();
    BatchObjectCryptoService crypto = new BatchObjectCryptoService(security, kms);
    return new SecretPayloadProtector(crypto);
  }
}
