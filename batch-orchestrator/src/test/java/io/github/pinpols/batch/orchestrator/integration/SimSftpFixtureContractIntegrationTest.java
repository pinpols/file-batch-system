package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** 使用真实 psql 执行共享夹具 SQL，验证规范字段、目标范围与失败原子性。 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("SIM SFTP 夹具字段与三通道初始化的 PostgreSQL 契约")
class SimSftpFixtureContractIntegrationTest {

  private static final Path REPOSITORY = Path.of(System.getProperty("user.dir")).getParent();

  @Container
  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.platform();

  private static JdbcTemplate jdbc;

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas("batch", "quartz")
        .defaultSchema("batch")
        .locations("classpath:db/migration")
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        .load()
        .migrate();
    jdbc = new JdbcTemplate(new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    POSTGRES.copyFileToContainer(
        MountableFile.forHostPath(REPOSITORY
            .resolve("scripts/sim/sql/prepare-sftp-probe-fixtures.sql")
            .toString()),
        "/tmp/prepare-sftp-probe-fixtures.sql");
  }

  @BeforeEach
  void seedChannels() {
    final String truncateFileChannelConfigSql = """
        truncate table batch.file_channel_config cascade
    """;

    jdbc.execute(truncateFileChannelConfigSql);
    insertChannel("ta", "ta_sftp_inbound");
    insertChannel("tb", "tb_sftp_inbound");
    insertChannel("tc", "tc_sftp_score");
    insertChannel("other-tenant", "ta_sftp_inbound");
    insertChannel("ta", "other_sftp_channel");
  }

  @AfterEach
  void removeFailureInjection() {
    final String dropTriggerSql = """
        drop trigger if exists reject_tc_probe_update on batch.file_channel_config
    """;
    final String dropFunctionSql = """
        drop function if exists batch.reject_tc_probe_update()
    """;

    jdbc.execute(dropTriggerSql);
    jdbc.execute(dropFunctionSql);
  }

  @Test
  @DisplayName("规范字段被保留且路由与严格公钥配置只应用于指定三通道,重复运行结果相同")
  void shouldPrepareOnlyOwnedCanonicalChannels_whenRunRepeatedly() throws Exception {
    final String selectConfigJsonSql = """
        select config_json->>'sftp_user' as username,
               config_json->>'sftp_password' as password,
               config_json->>'sftp_remote_directory' as directory,
               config_json->>'sftp_host' as host,
               config_json->>'sftp_port' as port,
               config_json->>'sftp_strict_host_key_checking' as strict,
               config_json->>'sftp_known_hosts_path' as known_hosts
          from batch.file_channel_config
         where tenant_id = 'ta' and channel_code = 'ta_sftp_inbound'
        """;

    List<Map<String, Object>> unrelatedBefore = unrelatedChannels();

    assertThat(runPreparation().getExitCode()).isZero();
    List<Map<String, Object>> firstConfiguration = channelConfigurations();
    assertThat(runPreparation().getExitCode()).isZero();

    assertThat(channelConfigurations()).isEqualTo(firstConfiguration);
    assertThat(unrelatedChannels()).isEqualTo(unrelatedBefore);
    Map<String, Object> selectedConfigJsonRow = jdbc.queryForMap(selectConfigJsonSql);
    assertThat(selectedConfigJsonRow)
        .containsEntry("username", "ta")
        .containsEntry("password", "fixture-password")
        .containsEntry("directory", "/inbound")
        .containsEntry("host", "127.0.0.1")
        .containsEntry("port", "12222")
        .containsEntry("strict", "yes")
        .containsEntry("known_hosts", "/tmp/pinned-known-hosts");
  }

  @Test
  @DisplayName("旧字段不能由初始化 SQL 转换成可运行配置,拒绝后全部通道保持原样")
  void shouldRejectLegacyFields_whenCanonicalFieldsAreMissing() throws Exception {
    final String updateFileChannelConfigSql = """
        update batch.file_channel_config
           set config_json = '{"sftp_username":"ta","sftp_remote_path":"/inbound"}'::jsonb
         where tenant_id = 'ta' and channel_code = 'ta_sftp_inbound'
        """;

    jdbc.update(updateFileChannelConfigSql);
    assertPreparationRejectedWithoutChanges();
  }

  @Test
  @DisplayName("已含规范字段但仍混入旧别名时拒绝,防止白名单忽略字段导致夹具漂移")
  void shouldRejectMixedAliases_whenCanonicalFieldsAlsoExist() throws Exception {
    final String updateFileChannelConfigSql = """
        update batch.file_channel_config
           set config_json = config_json || '{"username":"other-user"}'::jsonb
         where tenant_id = 'ta' and channel_code = 'ta_sftp_inbound'
        """;

    jdbc.update(updateFileChannelConfigSql);
    assertPreparationRejectedWithoutChanges();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"sftp_user\":\"\",\"sftp_remote_directory\":\"/inbound\"}",
        "{\"sftp_user\":true,\"sftp_remote_directory\":\"/inbound\"}",
        "{\"sftp_user\":\"ta\"}",
        "{\"sftp_user\":\"ta\",\"sftp_remote_directory\":\"  \"}"
      })
  @DisplayName("规范用户名和目录缺失,为空或类型不合法时整批拒绝")
  void shouldRejectInvalidCanonicalConfiguration_whenRequiredFieldIsInvalid(String configuration)
      throws Exception {
    final String updateFileChannelConfigSql = """
        update batch.file_channel_config set config_json = ?::jsonb
         where tenant_id = 'ta' and channel_code = 'ta_sftp_inbound'
        """;

    jdbc.update(updateFileChannelConfigSql, configuration);
    assertPreparationRejectedWithoutChanges();
  }

  @Test
  @DisplayName("三个指定目标缺失一个时不能仅更新剩余目标")
  void shouldRejectIncompleteScope_whenAnOwnedChannelIsMissing() throws Exception {
    final String deleteFileChannelConfigSql = """
        delete from batch.file_channel_config
         where tenant_id = 'tc' and channel_code = 'tc_sftp_score'
        """;

    jdbc.update(deleteFileChannelConfigSql);
    assertPreparationRejectedWithoutChanges();
  }

  @Test
  @DisplayName("指定编码对应其它通道类型时不能把它或其余目标隐式修正")
  void shouldRejectIncorrectType_whenAnOwnedChannelIsNotSftp() throws Exception {
    final String updateFileChannelConfigSql = """
        update batch.file_channel_config set channel_type = 'API'
         where tenant_id = 'tc' and channel_code = 'tc_sftp_score'
        """;

    jdbc.update(updateFileChannelConfigSql);
    assertPreparationRejectedWithoutChanges();
  }

  @Test
  @DisplayName("数据库只接受部分更新时整个准备事务回滚,不留下两条已修改的通道")
  void shouldRollBackAllChanges_whenDatabaseRejectsOneUpdate() throws Exception {
    final String createFunctionSql = """
        create function batch.reject_tc_probe_update() returns trigger language plpgsql as $$
        begin
          if new.tenant_id = 'tc' then return null; end if;
          return new;
        end
        $$
        """;
    final String createTriggerSql = """
        create trigger reject_tc_probe_update before update on batch.file_channel_config
        for each row execute function batch.reject_tc_probe_update()
        """;

    jdbc.execute(createFunctionSql);
    jdbc.execute(createTriggerSql);
    assertPreparationRejectedWithoutChanges();
  }

  @Test
  @DisplayName("启动 bootstrap 不再执行旧 SFTP 字段回填转换")
  void shouldRetireLegacySftpConversion_whenBootstrapIsLoaded() throws Exception {
    String bootstrap = Files.readString(REPOSITORY.resolve("test-data/sim/sim-e2e-bootstrap.sql"));
    assertThat(bootstrap).doesNotContain("THEN 'sftp_username'", "THEN 'sftp_remote_path'");
  }

  private void assertPreparationRejectedWithoutChanges() throws Exception {
    List<Map<String, Object>> before = channelSnapshot();
    var result = runPreparation();
    assertThat(result.getExitCode()).isNotZero();
    assertThat(result.getStderr()).contains("canonical SFTP fixture");
    assertThat(channelSnapshot()).isEqualTo(before);
  }

  private ExecResult runPreparation() throws Exception {
    return POSTGRES.execInContainer(
        "psql",
        "-X",
        "-q",
        "-U",
        POSTGRES.getUsername(),
        "-d",
        POSTGRES.getDatabaseName(),
        "-v",
        "ON_ERROR_STOP=1",
        "-v",
        "sftp_host=127.0.0.1",
        "-v",
        "sftp_port=12222",
        "-v",
        "known_hosts_path=/tmp/pinned-known-hosts",
        "-f",
        "/tmp/prepare-sftp-probe-fixtures.sql");
  }

  private void insertChannel(String tenant, String code) {
    final String insertFileChannelConfigSql = """
        insert into batch.file_channel_config (
          tenant_id, channel_code, channel_name, channel_type, config_json, auth_type
        ) values (?, ?, 'SFTP contract fixture', 'SFTP', jsonb_build_object(
          'sftp_user', ?::text, 'sftp_password', 'fixture-password',
          'sftp_remote_directory', '/inbound', 'sftp_host', 'before-host', 'sftp_port', 22
        ), 'PASSWORD')
        """;

    jdbc.update(insertFileChannelConfigSql, tenant, code, tenant);
  }

  private List<Map<String, Object>> channelSnapshot() {
    final String selectTenantIdSql = """
        select tenant_id, channel_code, config_json::text, updated_at
          from batch.file_channel_config order by tenant_id, channel_code
        """;

    return jdbc.queryForList(selectTenantIdSql);
  }

  private List<Map<String, Object>> channelConfigurations() {
    final String selectTenantIdSql = """
        select tenant_id, channel_code, config_json::text
          from batch.file_channel_config order by tenant_id, channel_code
        """;

    return jdbc.queryForList(selectTenantIdSql);
  }

  private List<Map<String, Object>> unrelatedChannels() {
    final String selectTenantIdSql = """
        select tenant_id, channel_code, config_json::text, updated_at
          from batch.file_channel_config
         where tenant_id = 'other-tenant' or channel_code = 'other_sftp_channel'
         order by tenant_id, channel_code
        """;

    return jdbc.queryForList(selectTenantIdSql);
  }
}
