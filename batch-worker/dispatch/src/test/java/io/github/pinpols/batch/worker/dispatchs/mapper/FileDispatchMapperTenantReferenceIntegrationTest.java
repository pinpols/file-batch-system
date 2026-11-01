package io.github.pinpols.batch.worker.dispatchs.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.testing.TestContainerLabels;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchJobContext;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchRecordSnapshot;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStageResult;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchDeliveryMetrics;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchFileContentResolver;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchRuntimeKeys;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchSecretResolver;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.FileDispatchRepository;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchChannelCircuitBreaker;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchChannelGateway;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchChannelHealthService;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.SftpDispatchChannelAdapter;
import io.github.pinpols.batch.worker.dispatchs.param.FileDispatchBeginAttemptParam;
import io.github.pinpols.batch.worker.dispatchs.param.FileDispatchBeginVerifiedAttemptParam;
import io.github.pinpols.batch.worker.dispatchs.param.FileDispatchInsertParam;
import io.github.pinpols.batch.worker.dispatchs.param.FileDispatchMarkAttemptFailedParam;
import io.github.pinpols.batch.worker.dispatchs.param.FileDispatchMarkSentParam;
import io.github.pinpols.batch.worker.dispatchs.stage.DeliverDispatchStep;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@DisplayName("分发记录 Mapper:数据库拒绝跨租户文件或 Pipeline 引用")
class FileDispatchMapperTenantReferenceIntegrationTest {

  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.create();
  private static final String SFTP_USER = "batch";
  private static final String SFTP_PASSWORD = "batch-pass";
  private static final GenericContainer<?> SFTP_SERVER = new GenericContainer<>(
          DockerImageName.parse("jmcombs/sftp:alpine"))
      .withLabel(TestContainerLabels.OWNER, TestContainerLabels.OWNER_VALUE)
      .withExposedPorts(22)
      .withCommand(SFTP_USER + ":" + SFTP_PASSWORD + ":::upload")
      .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));

  private JdbcTemplate jdbcTemplate;
  private SqlSessionFactory sqlSessionFactory;

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
    SFTP_SERVER.start();
  }

  @AfterAll
  static void stopPostgres() {
    SFTP_SERVER.stop();
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
    final String createBatchFileRecordSql = """
        create table batch.file_record(id bigint, tenant_id varchar(64))
    """;
    jdbcTemplate.execute(createBatchFileRecordSql);
    final String createBatchPipelineInstanceSql = """
        create table batch.pipeline_instance(
          id bigint, tenant_id varchar(64), related_job_instance_id bigint
        )
    """;
    jdbcTemplate.execute(createBatchPipelineInstanceSql);
    final String createBatchTriggerRequestSql = """
        create table batch.trigger_request(
          id bigint primary key, tenant_id varchar(64), parent_workflow_run_id bigint,
          launch_payload jsonb
        )
    """;
    jdbcTemplate.execute(createBatchTriggerRequestSql);
    final String createBatchWorkflowRunSql = """
        create table batch.workflow_run(
          id bigint primary key, tenant_id varchar(64), trigger_request_id bigint
        )
    """;
    jdbcTemplate.execute(createBatchWorkflowRunSql);
    final String createBatchJobInstanceSql = """
        create table batch.job_instance(
          id bigint primary key, tenant_id varchar(64), trigger_request_id bigint,
          job_code varchar(128)
        )
    """;
    jdbcTemplate.execute(createBatchJobInstanceSql);
    final String createBatchJobTaskSql = """
        create table batch.job_task(
          id bigint primary key, tenant_id varchar(64), job_instance_id bigint,
          task_status varchar(32),
          error_code varchar(64), external_outcome_resolution_version bigint,
          external_outcome_resolution_trigger_request_id bigint,
          external_outcome_resolution_consumed_at timestamptz,
          task_payload jsonb, version bigint, updated_at timestamptz default now()
        )
    """;
    jdbcTemplate.execute(createBatchJobTaskSql);
    final String createBatchFileChannelConfigSql = """
        create table batch.file_channel_config(
          tenant_id varchar(64), channel_code varchar(128), enabled boolean, is_deleted boolean
        )
    """;
    jdbcTemplate.execute(createBatchFileChannelConfigSql);
    final String createBatchFileDispatchRecordSql = """
        create table batch.file_dispatch_record (
          id bigserial primary key, tenant_id varchar(64), file_id bigint, pipeline_instance_id bigint,
          channel_code varchar(128), dispatch_target varchar(256), dispatch_status varchar(32),
          dispatch_attempt integer, receipt_code varchar(128), receipt_status varchar(32),
          external_request_id varchar(128), error_code varchar(64), error_message varchar(1024),
          dispatched_at timestamptz, ack_at timestamptz, updated_at timestamptz default now(),
          constraint uq_file_dispatch_record_tenant_file_channel
            unique (tenant_id, file_id, channel_code)
        )
        """;
    jdbcTemplate.execute(createBatchFileDispatchRecordSql);
    Configuration configuration =
        new Configuration(new Environment("it", new JdbcTransactionFactory(), dataSource));
    try (InputStream xml = Resources.getResourceAsStream("mapper/FileDispatchMapper.xml")) {
      new XMLMapperBuilder(
              xml, configuration, "mapper/FileDispatchMapper.xml", configuration.getSqlFragments())
          .parse();
    }
    sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
  }

  @Test
  @DisplayName("仅创建同租户 file_record 与 pipeline_instance 的关联记录")
  void insertDispatchRecord_enforcesTenantOwnershipForBothReferences() {
    final String insertBatchFileRecordSql = """
        insert into batch.file_record(id, tenant_id) values (10, 't1'), (20, 't2')
    """;
    jdbcTemplate.update(insertBatchFileRecordSql);
    final String insertBatchPipelineInstanceSql = """
        insert into batch.pipeline_instance(id, tenant_id) values (100, 't1'), (200, 't2')
    """;
    jdbcTemplate.update(insertBatchPipelineInstanceSql);

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      FileDispatchMapper mapper = session.getMapper(FileDispatchMapper.class);
      assertThat(mapper.insertDispatchRecord(params("t1", 10L, 100L))).isEqualTo(1);
      assertThat(mapper.insertDispatchRecord(params("t1", 10L, 100L))).isZero();
      assertThat(mapper.insertDispatchRecord(params("t1", 20L, 100L))).isZero();
      assertThat(mapper.insertDispatchRecord(params("t1", 10L, 200L))).isZero();
      DispatchRecordSnapshot snapshot = mapper.selectLatestDispatchRecord("t1", 10L, "CH_TEST");
      assertThat(snapshot.dispatchStatus()).isEqualTo("CREATED");
      assertThat(snapshot.receiptStatus()).isEqualTo("NONE");
      assertThat(snapshot.externalRequestId()).isEqualTo("request");
      assertThat(snapshot.receiptCode()).isEqualTo("receipt");
    }
    final String selectBatchFileDispatchRecordSql = """
        select count(*) from batch.file_dispatch_record
    """;
    Integer selectedFileDispatchRecordCount =
        jdbcTemplate.queryForObject(selectBatchFileDispatchRecordSql, Integer.class);
    assertThat(selectedFileDispatchRecordCount).isEqualTo(1);
  }

  @Test
  @DisplayName("冻结配置执行前仍要求同租户渠道未删除且启用")
  void isChannelActive_requiresEnabledNonDeletedTenantChannel() {
    final String insertBatchFileChannelConfigSql = """
        insert into batch.file_channel_config(tenant_id, channel_code, enabled, is_deleted)
        values ('t1', 'ACTIVE', true, false),
               ('t1', 'DISABLED', false, false),
               ('t1', 'DELETED', true, true),
               ('t2', 'OTHER_TENANT', true, false)
        """;
    jdbcTemplate.update(insertBatchFileChannelConfigSql);

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      FileDispatchRepository repository =
          new FileDispatchRepository(session.getMapper(FileDispatchMapper.class));

      assertThat(repository.isChannelActive("t1", "ACTIVE")).isTrue();
      assertThat(repository.isChannelActive("t1", "DISABLED")).isFalse();
      assertThat(repository.isChannelActive("t1", "DELETED")).isFalse();
      assertThat(repository.isChannelActive("t1", "OTHER_TENANT")).isFalse();
    }
  }

  @Test
  @DisplayName("未知外部结果不可自动重试,已知失败重试前必须重新建立 CREATED 外发闸门")
  void dispatchAttemptCas_blocksUnknownAndReopensKnownFailure() {
    final String insertBatchFileRecordSqlValue1 = """
        insert into batch.file_record(id, tenant_id) values (10, 't1')
    """;
    jdbcTemplate.update(insertBatchFileRecordSqlValue1);
    final String insertBatchPipelineInstanceSqlValue2 = """
        insert into batch.pipeline_instance(id, tenant_id) values (100, 't1')
    """;
    jdbcTemplate.update(insertBatchPipelineInstanceSqlValue2);

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      FileDispatchMapper mapper = session.getMapper(FileDispatchMapper.class);
      assertThat(mapper.insertDispatchRecord(params("t1", 10L, 100L))).isEqualTo(1);
      FileDispatchMarkAttemptFailedParam unknownOutcomeFailure =
          FileDispatchMarkAttemptFailedParam.builder()
              .tenantId("t1")
              .fileId(10L)
              .channelCode("CH_TEST")
              .dispatchStatus("FAILED")
              .receiptStatus("FAILED")
              .expectedDispatchStatus("CREATED")
              .errorCode("EXTERNAL_OUTCOME_UNKNOWN")
              .errorMessage("remote outcome unknown")
              .build();
      assertThat(mapper.markAttemptFailed(unknownOutcomeFailure)).isEqualTo(1);
      FileDispatchBeginAttemptParam attemptParams = FileDispatchBeginAttemptParam.builder()
          .tenantId("t1")
          .fileId(10L)
          .channelCode("CH_TEST")
          .unknownOutcomeCode("EXTERNAL_OUTCOME_UNKNOWN")
          .dispatchStatus("CREATED")
          .receiptStatus("NONE")
          .build();
      int unknownOutcomeRetryCount = mapper.beginAttempt(attemptParams);
      assertThat(unknownOutcomeRetryCount).isZero();

      DispatchRecordSnapshot unknown = mapper.selectLatestDispatchRecord("t1", 10L, "CH_TEST");
      assertThat(unknown.dispatchStatus()).isEqualTo("FAILED");
      assertThat(unknown.errorCode()).isEqualTo("EXTERNAL_OUTCOME_UNKNOWN");

      final String updateBatchFileDispatchRecordSql = """
          update batch.file_dispatch_record set error_code = 'SMTP_CONNECT_FAILED' where tenant_id = 't1'\s\
          and file_id = 10 and channel_code = 'CH_TEST'\
          """;
      jdbcTemplate.update(updateBatchFileDispatchRecordSql);
      int knownFailureRetryCount = mapper.beginAttempt(attemptParams);
      assertThat(knownFailureRetryCount).isEqualTo(1);
      final String selectBatchFileDispatchRecordSqlValue3 = """
          select dispatch_status from batch.file_dispatch_record where tenant_id = 't1' and file_id = 10\s\
          and channel_code = 'CH_TEST'\
          """;
      String selectedDispatchStatus =
          jdbcTemplate.queryForObject(selectBatchFileDispatchRecordSqlValue3, String.class);
      assertThat(selectedDispatchStatus).isEqualTo("CREATED");
      final String selectBatchFileDispatchRecordSqlValue4 = """
          select dispatch_attempt from batch.file_dispatch_record where tenant_id = 't1' and file_id = 10\s\
          and channel_code = 'CH_TEST'\
          """;
      Integer selectedDispatchAttempt =
          jdbcTemplate.queryForObject(selectBatchFileDispatchRecordSqlValue4, Integer.class);
      assertThat(selectedDispatchAttempt).isEqualTo(2);
      FileDispatchMarkSentParam sentAttempt = FileDispatchMarkSentParam.builder()
          .tenantId("t1")
          .fileId(10L)
          .channelCode("CH_TEST")
          .dispatchStatus("SENT")
          .externalRequestId("request-2")
          .receiptCode("receipt-2")
          .receiptStatus("NONE")
          .expectedDispatchStatus("CREATED")
          .build();
      assertThat(mapper.markSent(sentAttempt)).isEqualTo(1);
      FileDispatchMarkAttemptFailedParam staleAttemptFailure =
          FileDispatchMarkAttemptFailedParam.builder()
              .tenantId("t1")
              .fileId(10L)
              .channelCode("CH_TEST")
              .dispatchStatus("FAILED")
              .receiptStatus("FAILED")
              .expectedDispatchStatus("CREATED")
              .errorCode("STALE_ATTEMPT")
              .errorMessage("must not overwrite SENT")
              .build();
      assertThat(mapper.markAttemptFailed(staleAttemptFailure)).isZero();
    }
  }

  @Test
  @DisplayName("核实未执行授权只由绑定的新 Workflow Pipeline 消费一次并真实发送到 SFTP")
  void verifiedUnknownOutcomeAuthorization_isBoundToWorkflowAndDispatchIdentity() throws Exception {
    final String insertBatchFileRecordSql = """
        insert into batch.file_record(id, tenant_id) values (10, 't1')
    """;
    jdbcTemplate.update(insertBatchFileRecordSql);
    final String insertRootTriggerRequestSql = """
        insert into batch.trigger_request(id, tenant_id, launch_payload)
        values (900, 't1', '{"params":{"_externalOutcomeResolutionTaskId":500,"_externalOutcomeResolutionVersion":3}}')
        """;
    jdbcTemplate.update(insertRootTriggerRequestSql);
    final String insertWorkflowRunSql = """
        insert into batch.workflow_run(id, tenant_id, trigger_request_id)
        values (800, 't1', 900), (810, 't1', 910)
        """;
    jdbcTemplate.update(insertWorkflowRunSql);
    final String insertChildTriggerRequestSql = """
        insert into batch.trigger_request(id, tenant_id, parent_workflow_run_id, launch_payload)
        values (901, 't1', 800, '{}'), (902, 't1', 800, '{}'), (911, 't1', 810, '{}')
        """;
    jdbcTemplate.update(insertChildTriggerRequestSql);
    final String insertOtherResolutionRequestSql = """
        insert into batch.trigger_request(id, tenant_id, launch_payload)
        values (910, 't1', '{"params":{"_externalOutcomeResolutionTaskId":500,"_externalOutcomeResolutionVersion":3}}')
    """;
    jdbcTemplate.update(insertOtherResolutionRequestSql);
    final String insertSourceJobInstanceSql = """
        insert into batch.job_instance(id, tenant_id, trigger_request_id, job_code)
        values (700, 't1', 899, 'DISPATCH')
        """;
    jdbcTemplate.update(insertSourceJobInstanceSql);
    final String insertSourcePipelineInstanceSql = """
        insert into batch.pipeline_instance(id, tenant_id, related_job_instance_id)
        values (100, 't1', 700)
        """;
    jdbcTemplate.update(insertSourcePipelineInstanceSql);
    final String insertReplayJobInstanceSql = """
        insert into batch.job_instance(id, tenant_id, trigger_request_id, job_code)
        values (701, 't1', 901, 'DISPATCH')
    """;
    jdbcTemplate.update(insertReplayJobInstanceSql);
    final String insertReplayPipelineInstanceSql = """
        insert into batch.pipeline_instance(id, tenant_id, related_job_instance_id)
        values (101, 't1', 701)
        """;
    jdbcTemplate.update(insertReplayPipelineInstanceSql);
    final String insertWrongJobInstanceSql = """
        insert into batch.job_instance(id, tenant_id, trigger_request_id, job_code)
        values (702, 't1', 902, 'IMPORT'), (703, 't1', 911, 'DISPATCH')
    """;
    jdbcTemplate.update(insertWrongJobInstanceSql);
    final String insertWrongPipelineInstancesSql = """
        insert into batch.pipeline_instance(id, tenant_id, related_job_instance_id)
        values (102, 't1', 702), (103, 't1', 703)
        """;
    jdbcTemplate.update(insertWrongPipelineInstancesSql);
    final String insertSourceTaskSql = """
        insert into batch.job_task(
          id, tenant_id, job_instance_id, task_status, error_code, external_outcome_resolution_version,
          external_outcome_resolution_trigger_request_id, task_payload, version
        ) values (
          500, 't1', 700, 'FAILED', 'EXTERNAL_OUTCOME_UNKNOWN', 3, 900,
          '{"fileId":"10","channelCode":"CH_TEST"}', 4
        )
        """;
    jdbcTemplate.update(insertSourceTaskSql);
    final String insertBatchFileDispatchRecordSql = """
        insert into batch.file_dispatch_record(
          tenant_id, file_id, pipeline_instance_id, channel_code, dispatch_target,
          dispatch_status, dispatch_attempt, receipt_code, receipt_status, external_request_id,
          error_code, error_message
        ) values (
          't1', 10, 100, 'CH_TEST', 'target', 'FAILED', 1, 'receipt', 'FAILED', 'request',
          'EXTERNAL_OUTCOME_UNKNOWN', 'remote outcome unknown'
        )
        """;
    jdbcTemplate.update(insertBatchFileDispatchRecordSql);
    final String selectSourcePipelineAssociationSql = """
        select pipeline_instance_id from batch.file_dispatch_record
        where tenant_id = 't1' and file_id = 10 and channel_code = 'CH_TEST'
        """;

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      FileDispatchMapper mapper = session.getMapper(FileDispatchMapper.class);
      FileDispatchBeginVerifiedAttemptParam wrongWorkflow =
          verifiedAttemptParams(703L, 103L, "CH_TEST");
      assertThat(mapper.beginVerifiedExternalOutcomeAttempt(wrongWorkflow)).isZero();

      FileDispatchBeginVerifiedAttemptParam wrongJob = verifiedAttemptParams(702L, 102L, "CH_TEST");
      assertThat(mapper.beginVerifiedExternalOutcomeAttempt(wrongJob)).isZero();

      FileDispatchBeginVerifiedAttemptParam wrongChannel =
          verifiedAttemptParams(701L, 101L, "OTHER");
      assertThat(mapper.beginVerifiedExternalOutcomeAttempt(wrongChannel)).isZero();
      Long sourcePipelineAssociationBeforeRetry =
          jdbcTemplate.queryForObject(selectSourcePipelineAssociationSql, Long.class);
      assertThat(sourcePipelineAssociationBeforeRetry).isEqualTo(100L);

      byte[] deliveredContent = "approved-external-outcome-retry".getBytes(StandardCharsets.UTF_8);
      String remoteFileName = "authorized-retry.txt";
      String remoteFilePath = "/home/" + SFTP_USER + "/upload/" + remoteFileName;
      ExecResult remoteFileBeforeRetry =
          SFTP_SERVER.execInContainer("sh", "-lc", "test ! -f " + remoteFilePath);
      assertThat(remoteFileBeforeRetry.getExitCode()).isZero();

      DispatchFileContentResolver fileContentResolver = mock(DispatchFileContentResolver.class);
      when(fileContentResolver.openInputStream(any()))
          .thenAnswer(invocation -> new ByteArrayInputStream(deliveredContent));
      BatchSecurityProperties securityProperties = mock(BatchSecurityProperties.class);
      when(securityProperties.isBypassMode()).thenReturn(true);
      MockEnvironment testEnvironment = new MockEnvironment();
      testEnvironment.setActiveProfiles("test");
      SftpDispatchChannelAdapter sftpAdapter =
          new SftpDispatchChannelAdapter(fileContentResolver, securityProperties, testEnvironment);
      DispatchChannelCircuitBreaker circuitBreaker = mock(DispatchChannelCircuitBreaker.class);
      when(circuitBreaker.allow(any())).thenReturn(true);
      DispatchChannelHealthService healthService = mock(DispatchChannelHealthService.class);
      when(healthService.allowDispatch(any())).thenReturn(true);
      DispatchDeliveryMetrics deliveryMetrics = mock(DispatchDeliveryMetrics.class);
      DispatchChannelGateway gateway = new DispatchChannelGateway(
          List.of(sftpAdapter), circuitBreaker, deliveryMetrics, healthService);
      DispatchSecretResolver secretResolver = mock(DispatchSecretResolver.class);
      when(secretResolver.resolveInto(eq("t1"), eq("CH_TEST"), any(), any())).thenReturn(Set.of());
      FileDispatchRepository repository = new FileDispatchRepository(mapper);
      DeliverDispatchStep deliver = new DeliverDispatchStep(
          repository, gateway, mock(PlatformFileRecordRepository.class), secretResolver);
      DispatchJobContext authorizedContext = dispatchContext(701L, 101L);
      String sftpHost = SFTP_SERVER.getHost();
      int sftpPort = SFTP_SERVER.getMappedPort(22);
      Map<String, Object> sftpChannelConfig = Map.ofEntries(
          Map.entry("channel_type", "SFTP"),
          Map.entry("channel_code", "CH_TEST"),
          Map.entry("sftp_host", sftpHost),
          Map.entry("sftp_port", sftpPort),
          Map.entry("sftp_user", SFTP_USER),
          Map.entry("sftp_password", SFTP_PASSWORD),
          Map.entry("sftp_remote_directory", "/upload/"),
          Map.entry("sftp_remote_file_name", remoteFileName),
          Map.entry("sftp_strict_host_key_checking", "no"));
      authorizedContext.getAttributes().put(PipelineRuntimeKeys.CHANNEL_CONFIG, sftpChannelConfig);
      authorizedContext
          .getAttributes()
          .put(
              PipelineRuntimeKeys.FILE_RECORD,
              Map.of("id", 10L, "original_file_name", remoteFileName));

      DispatchStageResult result = deliver.execute(authorizedContext);

      assertThat(result.success()).isTrue();
      ExecResult remoteFileAfterRetry = SFTP_SERVER.execInContainer(
          "sh", "-lc", "test -f " + remoteFilePath + " && cat " + remoteFilePath);
      assertThat(remoteFileAfterRetry.getExitCode()).isZero();
      String expectedRemoteContent = new String(deliveredContent, StandardCharsets.UTF_8);
      String actualRemoteContent = remoteFileAfterRetry.getStdout();
      assertThat(actualRemoteContent).isEqualTo(expectedRemoteContent);
      DispatchStageResult repeatedDelivery = deliver.execute(authorizedContext);
      assertThat(repeatedDelivery.success()).isTrue();
      verify(fileContentResolver, times(1)).openInputStream(any());
      FileDispatchBeginVerifiedAttemptParam consumedGrantAttempt =
          verifiedAttemptParams(701L, 101L, "CH_TEST");
      int consumedGrantRows = mapper.beginVerifiedExternalOutcomeAttempt(consumedGrantAttempt);
      assertThat(consumedGrantRows).isZero();
    }

    final String selectAuthorizedDispatchAttemptSql = """
        select dispatch_status, dispatch_attempt, error_code
        from batch.file_dispatch_record
        where tenant_id = 't1' and file_id = 10 and channel_code = 'CH_TEST'
        """;
    Map<String, Object> dispatchRecord =
        jdbcTemplate.queryForMap(selectAuthorizedDispatchAttemptSql);
    assertThat(dispatchRecord.get("dispatch_status")).isEqualTo("SENT");
    assertThat(dispatchRecord.get("dispatch_attempt")).isEqualTo(2);
    assertThat(dispatchRecord.get("error_code")).isNull();
    Long sourcePipelineAssociationAfterRetry =
        jdbcTemplate.queryForObject(selectSourcePipelineAssociationSql, Long.class);
    assertThat(sourcePipelineAssociationAfterRetry).isEqualTo(101L);

    final String selectResolutionConsumptionSql = """
        select external_outcome_resolution_consumed_at is not null
        from batch.job_task where tenant_id = 't1' and id = 500
        """;
    Boolean selectedResolutionConsumption =
        jdbcTemplate.queryForObject(selectResolutionConsumptionSql, Boolean.class);
    assertThat(selectedResolutionConsumption).isTrue();
  }

  @Test
  @DisplayName("Worker 在外部接受后被强杀时,真实 PostgreSQL CREATED 台账阻止重领重复外发")
  void deliverRecovery_blocksRepeatAfterExternalAcceptAndWorkerCrash() {
    final String insertBatchFileRecordSqlValue5 = """
        insert into batch.file_record(id, tenant_id) values (10, 't1')
    """;
    jdbcTemplate.update(insertBatchFileRecordSqlValue5);
    final String insertBatchPipelineInstanceSqlValue6 = """
        insert into batch.pipeline_instance(id, tenant_id) values (100, 't1')
    """;
    jdbcTemplate.update(insertBatchPipelineInstanceSqlValue6);
    AtomicInteger acceptedMessages = new AtomicInteger();
    DispatchChannelGateway gateway = mock(DispatchChannelGateway.class);
    when(gateway.dispatch(any())).thenAnswer(invocation -> {
      acceptedMessages.incrementAndGet();
      throw new SimulatedWorkerCrash();
    });
    DispatchSecretResolver secretResolver = mock(DispatchSecretResolver.class);
    when(secretResolver.resolveInto(eq("t1"), eq("CH_TEST"), any(), any())).thenReturn(Set.of());

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      FileDispatchRepository repository =
          new FileDispatchRepository(session.getMapper(FileDispatchMapper.class));
      DeliverDispatchStep deliver = new DeliverDispatchStep(
          repository, gateway, mock(PlatformFileRecordRepository.class), secretResolver);

      assertThatThrownBy(() -> deliver.execute(dispatchContext()))
          .isInstanceOf(SimulatedWorkerCrash.class);
      DispatchRecordSnapshot afterCrash =
          repository.latestDispatchRecord("t1", 10L, "CH_TEST").orElseThrow();
      assertThat(afterCrash.dispatchStatus()).isEqualTo("CREATED");
      assertThat(acceptedMessages).hasValue(1);

      DispatchStageResult recovered = deliver.execute(dispatchContext());

      assertThat(recovered.success()).isFalse();
      assertThat(recovered.code()).isEqualTo("EXTERNAL_OUTCOME_UNKNOWN");
      DispatchRecordSnapshot afterRecovery =
          repository.latestDispatchRecord("t1", 10L, "CH_TEST").orElseThrow();
      assertThat(afterRecovery.dispatchStatus()).isEqualTo("FAILED");
      assertThat(afterRecovery.errorCode()).isEqualTo("EXTERNAL_OUTCOME_UNKNOWN");
      assertThat(acceptedMessages).hasValue(1);
      verify(gateway, times(1)).dispatch(any());
    }
  }

  @Test
  @DisplayName("具名参数保留发送与确认 CAS、可空回执码、补偿回执策略及租户隔离")
  void shouldPreserveDispatchReceiptTransitionsWithTypedParams() {
    final String insertFilesSql = """
        insert into batch.file_record(id, tenant_id)
        values (10, 't1'), (11, 't1'), (10, 't2')
        """;
    jdbcTemplate.update(insertFilesSql);
    final String insertPipelinesSql = """
        insert into batch.pipeline_instance(id, tenant_id)
        values (100, 't1'), (200, 't2')
        """;
    jdbcTemplate.update(insertPipelinesSql);

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      FileDispatchRepository repository =
          new FileDispatchRepository(session.getMapper(FileDispatchMapper.class));
      FileDispatchRepository.InsertDispatchParam first =
          new FileDispatchRepository.InsertDispatchParam(
              "t1", 10L, 100L, "CH_TEST", "target", "receipt", "NONE", "request");
      FileDispatchRepository.InsertDispatchParam failed =
          new FileDispatchRepository.InsertDispatchParam(
              "t1", 11L, 100L, "CH_TEST", "target", null, "NONE", "request-failed");
      FileDispatchRepository.InsertDispatchParam otherTenant =
          new FileDispatchRepository.InsertDispatchParam(
              "t2", 10L, 200L, "CH_TEST", "target", "other-receipt", "NONE", "other-request");
      assertThat(repository.insertDispatchRecord(first)).isEqualTo(1);
      assertThat(repository.insertDispatchRecord(failed)).isEqualTo(1);
      assertThat(repository.insertDispatchRecord(otherTenant)).isEqualTo(1);

      assertThat(repository.markAcked("t1", 10L, "CH_TEST", "too-early")).isZero();
      assertThat(repository.markSent("t1", 10L, "CH_TEST", "sent-request", null, "PENDING"))
          .isEqualTo(1);
      DispatchRecordSnapshot sent =
          repository.latestDispatchRecord("t1", 10L, "CH_TEST").orElseThrow();
      assertThat(sent.dispatchStatus()).isEqualTo("SENT");
      assertThat(sent.receiptCode()).isEqualTo("receipt");
      assertThat(sent.externalRequestId()).isEqualTo("sent-request");
      assertThat(repository.listPendingReceiptPolls(10, 3600)).singleElement().satisfies(row -> {
        assertThat(row.tenantId()).isEqualTo("t1");
        assertThat(row.fileId()).isEqualTo(10L);
        assertThat(row.channelCode()).isEqualTo("CH_TEST");
        assertThat(row.externalRequestId()).isEqualTo("sent-request");
      });

      assertThat(repository.markSent("t1", 10L, "CH_TEST", "stale", "stale", "NONE"))
          .isZero();
      assertThat(repository.markAcked("t1", 10L, "CH_TEST", "ack-receipt")).isEqualTo(1);
      assertThat(repository.markAcked("t1", 10L, "CH_TEST", "stale-ack")).isZero();
      assertThat(repository.listPendingReceiptPolls(10, 3600)).isEmpty();
      assertThat(repository.markCompensated("t1", 10L, "CH_TEST", null, null)).isEqualTo(1);
      DispatchRecordSnapshot compensated =
          repository.latestDispatchRecord("t1", 10L, "CH_TEST").orElseThrow();
      assertThat(compensated.dispatchStatus()).isEqualTo("COMPENSATED");
      assertThat(compensated.receiptStatus()).isEqualTo("SUCCESS");
      assertThat(compensated.receiptCode()).isEqualTo("ack-receipt");

      assertThat(repository.markFailed("t1", 11L, "CH_TEST", "KNOWN_FAILURE", null))
          .isEqualTo(1);
      assertThat(repository.markCompensated("t1", 11L, "CH_TEST", "COMPENSATION", "failed"))
          .isEqualTo(1);
      DispatchRecordSnapshot failedCompensation =
          repository.latestDispatchRecord("t1", 11L, "CH_TEST").orElseThrow();
      assertThat(failedCompensation.dispatchStatus()).isEqualTo("COMPENSATED");
      assertThat(failedCompensation.receiptStatus()).isEqualTo("FAILED");
      assertThat(failedCompensation.errorCode()).isEqualTo("COMPENSATION");
      assertThat(failedCompensation.errorMessage()).isEqualTo("failed");
      DispatchRecordSnapshot untouched =
          repository.latestDispatchRecord("t2", 10L, "CH_TEST").orElseThrow();
      assertThat(untouched.dispatchStatus()).isEqualTo("CREATED");
      assertThat(untouched.externalRequestId()).isEqualTo("other-request");
      assertThat(untouched.receiptCode()).isEqualTo("other-receipt");
    }
  }

  private static DispatchJobContext dispatchContext() {
    return dispatchContext(null, 100L);
  }

  private static DispatchJobContext dispatchContext(Long jobInstanceId, Long pipelineInstanceId) {
    DispatchPayload payload = new DispatchPayload(
        "10", null, "CH_TEST", "target", "request", "receipt", true, false, "SYNC", Map.of());
    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    context.getAttributes().put(DispatchRuntimeKeys.DISPATCH_PAYLOAD, payload);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, 10L);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_RECORD, Map.of("id", 10L));
    context
        .getAttributes()
        .put(PipelineRuntimeKeys.CHANNEL_CONFIG, Map.of("channel_type", "EMAIL"));
    if (jobInstanceId != null) {
      context.getAttributes().put(PipelineRuntimeKeys.JOB_INSTANCE_ID, jobInstanceId);
    }
    context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, pipelineInstanceId);
    context.getAttributes().put(PipelineRuntimeKeys.TRACE_ID, "trace-dispatch-reclaim");
    return context;
  }

  private static final class SimulatedWorkerCrash extends Error {

    private static final long serialVersionUID = 1L;
  }

  private static FileDispatchInsertParam params(
      String tenantId, Long fileId, Long pipelineInstanceId) {
    return FileDispatchInsertParam.builder()
        .tenantId(tenantId)
        .fileId(fileId)
        .pipelineInstanceId(pipelineInstanceId)
        .channelCode("CH_TEST")
        .dispatchTarget("target")
        .dispatchStatus("CREATED")
        .receiptCode("receipt")
        .receiptStatus("NONE")
        .externalRequestId("request")
        .build();
  }

  private static FileDispatchBeginVerifiedAttemptParam verifiedAttemptParams(
      Long jobInstanceId, Long pipelineInstanceId, String channelCode) {
    return FileDispatchBeginVerifiedAttemptParam.builder()
        .tenantId("t1")
        .fileId(10L)
        .channelCode(channelCode)
        .jobInstanceId(jobInstanceId)
        .pipelineInstanceId(pipelineInstanceId)
        .unknownOutcomeCode("EXTERNAL_OUTCOME_UNKNOWN")
        .dispatchStatus("CREATED")
        .receiptStatus("NONE")
        .build();
  }
}
