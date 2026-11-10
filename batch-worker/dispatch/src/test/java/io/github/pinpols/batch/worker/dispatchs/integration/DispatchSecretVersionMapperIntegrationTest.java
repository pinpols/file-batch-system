package io.github.pinpols.batch.worker.dispatchs.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchJobContext;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStageResult;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchRuntimeKeys;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchSecretResolver;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.FileDispatchRepository;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchChannelGateway;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchCommand;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchResult;
import io.github.pinpols.batch.worker.dispatchs.mapper.DispatchSecretVersionMapper;
import io.github.pinpols.batch.worker.dispatchs.stage.DeliverDispatchStep;
import io.github.pinpols.batch.worker.dispatchs.stage.RetryDispatchStep;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DisplayName("分发密钥版本 mapper 的真实 PostgreSQL 语义")
class DispatchSecretVersionMapperIntegrationTest {

  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.platform();
  private static DataSource dataSource;
  private static JdbcTemplate jdbcTemplate;
  private static SqlSessionFactory sqlSessionFactory;

  @BeforeAll
  static void initializeDatabase() throws Exception {
    POSTGRES.start();
    PGSimpleDataSource postgresDataSource = new PGSimpleDataSource();
    postgresDataSource.setURL(POSTGRES.getJdbcUrl());
    postgresDataSource.setUser(POSTGRES.getUsername());
    postgresDataSource.setPassword(POSTGRES.getPassword());
    dataSource = postgresDataSource;
    jdbcTemplate = new JdbcTemplate(dataSource);
    final String createSql = """
        create schema batch
    """;
    jdbcTemplate.execute(createSql);
    final String createBatchSecretVersionSql = """
        create table batch.secret_version (
          id bigserial primary key,
          tenant_id varchar(64) not null,
          secret_ref varchar(128) not null,
          secret_name varchar(256) not null,
          version_no integer not null,
          secret_status varchar(32) not null,
          current_version boolean not null default false,
          effective_from_at timestamptz,
          effective_to_at timestamptz,
          secret_payload jsonb not null
        )
        """;
    jdbcTemplate.execute(createBatchSecretVersionSql);
    final String createBatchConfigChangeLogSql = """
        create table batch.config_change_log (
          change_action varchar(32) not null,
          constraint ck_config_change_log_action
            check (change_action in (
              'CREATE', 'PUBLISH', 'GRAY', 'ROLLBACK', 'ROTATE',
              'SUBMIT_APPROVAL', 'APPROVE', 'REJECT'
            ))
        )
        """;
    jdbcTemplate.execute(createBatchConfigChangeLogSql);
    new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V234__secret_version_dispatch_revocation.sql"))
        .execute(dataSource);

    Configuration configuration = new Configuration(
        new Environment("dispatch-secret-it", new JdbcTransactionFactory(), dataSource));
    String mapperPath = "mapper/DispatchSecretVersionMapper.xml";
    try (var mapperXml = Resources.getResourceAsStream(mapperPath)) {
      new XMLMapperBuilder(mapperXml, configuration, mapperPath, configuration.getSqlFragments())
          .parse();
    }
    sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @Test
  @DisplayName("只返回同租户的固定版本,并在撤销或到期后立即拒绝读取")
  void selectsPinnedVersion_whenVersionIsValidAndRejectsAfterExpiry() {
    final String insertBatchSecretVersionSql = """
        insert into batch.secret_version (
          id, tenant_id, secret_ref, secret_name, version_no, secret_status,
          current_version, effective_from_at, effective_to_at, secret_payload
        ) values
          (101, 'tenant-a', 'dispatch/key', 'key', 1, 'ROLLED_BACK', false, null, null,\s\
          '{"format":"BATCHENC_BASE64_V1"}'),
          (102, 'tenant-b', 'dispatch/key', 'key', 1, 'PUBLISHED', true, null, null,\s\
          '{"format":"other-tenant"}'),
          (103, 'tenant-a', 'dispatch/key', 'key', 2, 'PUBLISHED', true, null, current_timestamp - interval\s\
          '1 second', '{"format":"expired"}')
        """;
    jdbcTemplate.update(insertBatchSecretVersionSql);
    try (SqlSession session = sqlSessionFactory.openSession()) {
      DispatchSecretVersionMapper mapper = session.getMapper(DispatchSecretVersionMapper.class);

      assertThat(mapper.selectPayloadForDispatch("tenant-a", 101L))
          .isEqualTo("{\"format\": \"BATCHENC_BASE64_V1\"}");
      assertThat(mapper.selectPayloadForDispatch("tenant-b", 101L)).isNull();
      assertThat(mapper.selectPayloadForDispatch("tenant-a", 102L)).isNull();
      assertThat(mapper.selectPayloadForDispatch("tenant-a", 103L)).isNull();

      final String updateBatchSecretVersionSql = """
          update batch.secret_version
             set revoked_at = current_timestamp, revoked_by = 'operator', revoke_reason = 'compromised'
           where id = 101
          """;
      jdbcTemplate.update(updateBatchSecretVersionSql);
      session.clearCache();
      assertThat(mapper.selectPayloadForDispatch("tenant-a", 101L)).isNull();
      session.commit();
    }
    final String selectInformationSchemaColumnsSql = """
        select count(*)
          from information_schema.columns
         where table_schema = 'batch' and table_name = 'secret_version'
           and column_name in ('revoked_at', 'revoked_by', 'revoke_reason')
        """;
    Integer selectedColumnsCount =
        jdbcTemplate.queryForObject(selectInformationSchemaColumnsSql, Integer.class);
    assertThat(selectedColumnsCount).isEqualTo(3);
    final String insertBatchConfigChangeLogSql = """
        insert into batch.config_change_log(change_action) values ('REVOKE')
    """;
    jdbcTemplate.update(insertBatchConfigChangeLogSql);
  }

  @Test
  @DisplayName("首次外发后撤销固定密钥,后续重试重新读取数据库并阻断外发")
  void rejectsRetry_whenPinnedVersionIsRevokedAfterFirstAttempt() {
    final String insertSecretSql = """
        insert into batch.secret_version (
          id, tenant_id, secret_ref, secret_name, version_no, secret_status,
          current_version, secret_payload
        ) values (201, 'tenant-retry', 'dispatch/key', 'key', 1, 'PUBLISHED', true,
                  '{"format":"BATCHENC_BASE64_V1"}')
        """;
    jdbcTemplate.update(insertSecretSql);

    SecretPayloadProtector protector = mock(SecretPayloadProtector.class);
    when(protector.reveal(any())).thenReturn("{\"sftp_password\":\"runtime-secret\"}");
    FileDispatchRepository repository = mock(FileDispatchRepository.class);
    when(repository.latestDispatchRecord(any(), any(), any())).thenReturn(Optional.empty());
    when(repository.insertDispatchRecord(any())).thenReturn(1);
    when(repository.markAttemptFailed(any(), any(), any(), any(), any())).thenReturn(1);
    PlatformFileRecordRepository fileRecords = mock(PlatformFileRecordRepository.class);
    DispatchChannelGateway gateway = mock(DispatchChannelGateway.class);
    when(gateway.dispatch(any())).thenAnswer(invocation -> {
      DispatchCommand command = invocation.getArgument(0);
      assertThat(command.channelConfig()).containsEntry("sftp_password", "runtime-secret");
      return DispatchResult.failureWithoutReceipt("temporary failure");
    });

    Map<String, Object> channelConfig =
        new LinkedHashMap<>(Map.of("channel_type", "SFTP", "secret_ref", "dispatch/key"));
    Map<String, Object> bindings =
        Map.of("CH1", Map.of("secretRef", "dispatch/key", "secretVersionId", 201));
    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("tenant-retry");
    context
        .getAttributes()
        .put(
            DispatchRuntimeKeys.DISPATCH_PAYLOAD,
            new DispatchPayload(
                "10", null, "CH1", "target", "request-201", null, null, null, null, null));
    context.getAttributes().put(DispatchRuntimeKeys.DISPATCH_SECRET_BINDINGS, bindings);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, 10L);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_RECORD, Map.of("id", 10L));
    context.getAttributes().put(PipelineRuntimeKeys.CHANNEL_CONFIG, channelConfig);

    try (SqlSession firstSession = sqlSessionFactory.openSession()) {
      DispatchSecretVersionMapper mapper =
          firstSession.getMapper(DispatchSecretVersionMapper.class);
      DispatchSecretResolver resolver =
          new DispatchSecretResolver(mapper, protector, new ObjectMapper());
      DeliverDispatchStep delivery =
          new DeliverDispatchStep(repository, gateway, fileRecords, resolver);
      DispatchStageResult firstResult = delivery.execute(context);
      assertThat(firstResult.code()).isEqualTo("DISPATCH_SEND_FAILED");
      assertThat(channelConfig).doesNotContainKey("sftp_password");
    }

    final String revokeSecretSql = """
        update batch.secret_version
           set revoked_at = current_timestamp, revoked_by = 'operator', revoke_reason = 'compromised'
         where tenant_id = ? and id = ?
        """;
    int revoked = jdbcTemplate.update(revokeSecretSql, "tenant-retry", 201L);
    assertThat(revoked).isEqualTo(1);
    try (SqlSession retrySession = sqlSessionFactory.openSession()) {
      DispatchSecretVersionMapper mapper =
          retrySession.getMapper(DispatchSecretVersionMapper.class);
      DispatchSecretResolver resolver =
          new DispatchSecretResolver(mapper, protector, new ObjectMapper());
      RetryDispatchStep retry = new RetryDispatchStep(repository, gateway, resolver);
      DispatchStageResult retryResult = retry.execute(context);
      assertThat(retryResult.success()).isFalse();
      assertThat(retryResult.code()).isEqualTo("DISPATCH_SECRET_REVOKED_OR_EXPIRED");
      assertThat(channelConfig).doesNotContainKey("sftp_password");
      verify(gateway, times(1)).dispatch(any());
      verify(repository, never()).beginAttempt(any(), any(), any());
    }
  }
}
