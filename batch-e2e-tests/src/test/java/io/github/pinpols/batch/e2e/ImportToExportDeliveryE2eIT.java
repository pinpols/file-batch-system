package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.e2e.apps.E2eDispatchApplication;
import io.github.pinpols.batch.e2e.apps.E2eExportApplication;
import io.github.pinpols.batch.e2e.apps.E2eImportApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eOutboxPublishSupport;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.e2e.support.E2eTestSql;
import io.github.pinpols.batch.e2e.support.verifier.DispatchReceiptVerifier;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.jdbc.Sql;

/** 验证同一份导入数据经真实 Import/Export worker 与对象存储形成可核对的交付产物。 */
@E2eSpringBootTest(
    classes = E2eImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "batch.worker.import.worker-type=IMPORT")
@E2eBusinessSchema
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Sql(
    scripts = {
      E2eTestSql.IMPORT_TEMPLATE_SEED,
      E2eTestSql.EXPORT_TEMPLATE_SEED,
    })
@DisplayName("同一业务输入经导入、数据库核对、导出和本地隔离派发完成交付闭环")
class ImportToExportDeliveryE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";

  @TempDir
  private Path dispatchTempDir;

  private final WorkflowRunLaunchService launchService;
  private final JdbcTemplate jdbcTemplate;
  private final E2eOutboxPublishSupport outboxPublishSupport;
  private final Environment environment;
  private final DataSource businessDataSource;

  ImportToExportDeliveryE2eIT(
      WorkflowRunLaunchService launchService,
      JdbcTemplate jdbcTemplate,
      E2eOutboxPublishSupport outboxPublishSupport,
      Environment environment,
      @Qualifier("importBusinessDataSource") DataSource businessDataSource) {
    this.launchService = launchService;
    this.jdbcTemplate = jdbcTemplate;
    this.outboxPublishSupport = outboxPublishSupport;
    this.environment = environment;
    this.businessDataSource = businessDataSource;
  }

  @Test
  @DisplayName("导入记录经业务库核对、MinIO 导出和 Dispatch 本地 outbox 完成交付闭环")
  void shouldDeliverImportedRecordThroughExportAndDispatchWorkers() throws Exception {
    final String selectCountSql = """
        select count(*)::int from biz.customer_account where tenant_id = ? and customer_no = ?
    """;

    final String updateFileTemplateConfigSql = """
        update batch.file_template_config
        set export_data_ref = 'sql_template_export',
            default_query_sql = ?,
            query_param_schema = ?::jsonb
        where tenant_id = ? and template_code = 'EXP-CUSTOMER-JSON'
        """;
    final String selectIdSql = """
        select id, storage_path from batch.file_record where tenant_id = ? and file_code = ?\s\
        and file_category = 'OUTPUT' order by id desc limit 1\
        """;

    // 交付链跨越多次运行，使用未到截止时间的业务日避免中途被结算。
    LocalDate bizDate = LocalDate.now(ZoneOffset.UTC).plusDays(1);
    String unique = "JOURNEY" + Long.toUnsignedString(System.nanoTime());
    String customerNo = "C-" + unique;
    String customerName = "Journey " + unique;
    Map<String, Object> importParams = new LinkedHashMap<>();
    importParams.put("fileFormatType", "JSON");
    importParams.put("templateCode", "IMP-CUSTOMER-JSON-ARRAY");
    importParams.put("bizType", "CUSTOMER");
    importParams.put("batchNo", unique);
    Map<String, Object> customer = new LinkedHashMap<>();
    customer.put("customerNo", customerNo);
    customer.put("customerName", customerName);
    customer.put("customerType", "PERSONAL");
    customer.put("certificateNo", "ID-" + unique);
    customer.put("mobileNo", "13800000001");
    customer.put("email", "journey@example.com");
    customer.put("status", "ACTIVE");
    importParams.put("content", JsonUtils.toJson(List.of(customer)));

    LaunchSeed importSeed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API, bizDate);
    launchService.launch(new LaunchRequest(
        TENANT,
        importSeed.workflowDefinitionId(),
        bizDate,
        TriggerType.API,
        importSeed.requestId(),
        "e2e-tr-import-export-import",
        importParams));
    outboxPublishSupport.publishAllPending(TENANT);
    awaitTaskSuccess(importSeed.dedupKey());

    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    Integer importedRows =
        businessJdbc.queryForObject(selectCountSql, Integer.class, TENANT, customerNo);
    assertThat(importedRows).isEqualTo(1);

    String exportSql = """
        SELECT id, customer_no, customer_name, customer_type, credit_limit, currency_code, email, status,\s\
        open_date FROM biz.customer_account WHERE tenant_id = :tenantId AND source_batch_no = :batchNo\s\
        ORDER BY id\
        """;
    jdbcTemplate.update(
        updateFileTemplateConfigSql,
        exportSql,
        JsonUtils.toJson(Map.of("sqlTemplateExport", Map.of("cursorColumn", "id"))),
        TENANT);

    try (ConfigurableApplicationContext exportWorker = startExportWorker()) {
      LaunchSeed exportSeed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
          jdbcTemplate, TENANT, "EXPORT", "export", TriggerType.API, bizDate);
      Map<String, Object> exportParams = Map.of(
          "batchNo",
          unique,
          "templateCode",
          "EXP-CUSTOMER-JSON",
          "bizDate",
          bizDate.toString(),
          "bizType",
          "CUSTOMER",
          "fileCode",
          "e2e-journey-" + unique);
      launchService.launch(new LaunchRequest(
          TENANT,
          exportSeed.workflowDefinitionId(),
          bizDate,
          TriggerType.API,
          exportSeed.requestId(),
          "e2e-tr-import-export-export",
          exportParams));
      outboxPublishSupport.publishAllPending(TENANT);
      awaitTaskSuccess(exportSeed.dedupKey());

      List<Map<String, Object>> exportedFiles =
          jdbcTemplate.queryForList(selectIdSql, TENANT, "e2e-journey-" + unique);
      assertThat(exportedFiles).hasSize(1);
      Long outputFileId = ((Number) exportedFiles.get(0).get("id")).longValue();
      String storagePath = String.valueOf(exportedFiles.get(0).get("storage_path"));
      assertThat(storagePath).isNotBlank();
      String objectKey = storagePath.startsWith(s3Bucket() + "/")
          ? storagePath.substring(s3Bucket().length() + 1)
          : storagePath;
      String exportedContent =
          new String(readObject(s3Bucket(), objectKey), StandardCharsets.UTF_8);
      assertThat(exportedContent).contains(customerNo, customerName);
      assertThat(exportedContent).doesNotContain("C-E2E");
      assertThat(exportWorker.isActive()).isTrue();

      dispatchExportedFile(outputFileId, unique, bizDate);
    }
  }

  private void dispatchExportedFile(Long fileId, String unique, LocalDate bizDate)
      throws Exception {
    final String insertFileChannelConfigSql = """
        insert into batch.file_channel_config (
            tenant_id, channel_code, channel_name, channel_type, target_endpoint, auth_type,
            config_json, receipt_policy, timeout_seconds, enabled
        ) values (
            ?, ?, 'Journey isolated local outbox', 'LOCAL', ?, 'NONE',
            jsonb_build_object(
                'target_endpoint', ?,
                'receipt_policy', 'NONE',
                'channel_type', 'LOCAL',
                'channel_code', ?
            ),
            'NONE', 10, true
        )
        """;
    final String selectFileStatusSql = """
        select file_status from batch.file_record where id = ?
    """;
    final String updateFileChannelConfigSql = """
        update batch.file_channel_config\s\
        set target_endpoint = ?, config_json = jsonb_set(config_json, '{target_endpoint}', to_jsonb(?::text),\s\
        true) where tenant_id = ? and channel_code = ?\
        """;
    final String selectWorkflowStatusSql = """
        select wr.run_status from batch.workflow_run wr
        join batch.trigger_request tr
          on tr.tenant_id = wr.tenant_id and tr.id = wr.trigger_request_id
        where wr.tenant_id = ? and tr.request_id = ?
        """;
    final String insertRetryRequestSql = """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, workflow_definition_id, biz_date,
            dedup_key, request_status, trace_id, dry_run
        ) values (?, ?, ?, ?, ?, ?, 'ACCEPTED', ?, false)
        """;

    String channelCode = "journey-local-" + unique;
    String receiptCode = "R-" + unique;
    Path outboxDirectory = dispatchTempDir.resolve("outbox");
    Files.createDirectories(outboxDirectory);
    Path blockedParent = dispatchTempDir.resolve("not-a-directory");
    Files.writeString(blockedParent, "dispatch target unavailable", StandardCharsets.UTF_8);
    Path unavailableTarget = blockedParent.resolve("outbox");
    jdbcTemplate.update(
        insertFileChannelConfigSql,
        TENANT,
        channelCode,
        unavailableTarget.toString(),
        unavailableTarget.toString(),
        channelCode);

    try (ConfigurableApplicationContext dispatchWorker = startDispatchWorker()) {
      LaunchSeed dispatchSeed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
          jdbcTemplate, TENANT, "DISPATCH", "dispatch", TriggerType.API, bizDate);
      String externalRequestId = "journey-" + unique;
      Map<String, Object> dispatchParams = Map.of(
          "fileId",
          fileId.toString(),
          "channelCode",
          channelCode,
          "dispatchTarget",
          outboxDirectory.toString(),
          "externalRequestId",
          externalRequestId,
          "receiptCode",
          receiptCode,
          "ackRequired",
          false,
          "forceRetry",
          false);
      launchService.launch(new LaunchRequest(
          TENANT,
          dispatchSeed.workflowDefinitionId(),
          bizDate,
          TriggerType.API,
          dispatchSeed.requestId(),
          "e2e-tr-import-export-dispatch",
          dispatchParams));
      outboxPublishSupport.publishAllPending(TENANT);

      awaitTaskStatus(dispatchSeed.dedupKey(), "FAILED");
      await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
        String workflowStatus = jdbcTemplate.queryForObject(
            selectWorkflowStatusSql, String.class, TENANT, dispatchSeed.requestId());
        assertThat(workflowStatus).isEqualTo("FAILED");
      });
      String failedFileStatus =
          jdbcTemplate.queryForObject(selectFileStatusSql, String.class, fileId);
      assertThat(failedFileStatus).isNotEqualTo(FileStatus.DISPATCHED.code());

      jdbcTemplate.update(
          updateFileChannelConfigSql,
          outboxDirectory.toString(),
          outboxDirectory.toString(),
          TENANT,
          channelCode);
      // 终态作业不可原地重排；修正渠道后，以同一 Workflow 定义发起新的受管运行。
      String retryRequestId = "journey-retry-" + unique;
      String retryDedupKey = "journey-retry-dedup-" + unique;
      jdbcTemplate.update(
          insertRetryRequestSql,
          TENANT,
          retryRequestId,
          TriggerType.API.code(),
          dispatchSeed.workflowDefinitionId(),
          bizDate,
          retryDedupKey,
          "e2e-tr-import-export-dispatch-retry");
      launchService.launch(new LaunchRequest(
          TENANT,
          dispatchSeed.workflowDefinitionId(),
          bizDate,
          TriggerType.API,
          retryRequestId,
          "e2e-tr-import-export-dispatch-retry",
          dispatchParams));
      outboxPublishSupport.publishAllPending(TENANT);
      awaitTaskSuccess(retryDedupKey);
      awaitTaskStatus(dispatchSeed.dedupKey(), "FAILED");

      DispatchReceiptVerifier.forTenant(TENANT)
          .fileId(fileId)
          .platformJdbc(jdbcTemplate)
          .expectedFileStatus(FileStatus.DISPATCHED.code())
          .expectedReceiptCode(receiptCode)
          .expectedChannelCode(channelCode)
          .expectedMinAuditCount(1)
          .build()
          .verify();

      List<Path> envelopes;
      try (var paths = Files.list(outboxDirectory)) {
        envelopes = paths
            .filter(path -> path.getFileName().toString().endsWith(".json"))
            .toList();
      }
      assertThat(envelopes).hasSize(1);
      String envelope = Files.readString(envelopes.get(0), StandardCharsets.UTF_8);
      assertThat(envelope).contains(channelCode, externalRequestId, receiptCode);
      assertThat(dispatchWorker.isActive()).isTrue();
    }
  }

  private ConfigurableApplicationContext startExportWorker() {
    Map<String, Object> properties = new LinkedHashMap<>();
    for (String key : List.of(
        "spring.datasource.url",
        "spring.datasource.username",
        "spring.datasource.password",
        "spring.datasource.driver-class-name",
        "spring.datasource.hikari.maximum-pool-size",
        "spring.datasource.hikari.minimum-idle",
        "spring.kafka.bootstrap-servers",
        "batch.datasource.business.url",
        "batch.datasource.business.username",
        "batch.datasource.business.password",
        "batch.datasource.business.schema",
        "spring.data.redis.host",
        "spring.data.redis.port",
        "batch.storage.backend",
        "batch.storage.filesystem.root",
        "batch.storage.filesystem.download-base-url",
        "batch.storage.filesystem.presign-secret",
        "batch.storage.s3.endpoint",
        "batch.storage.s3.access-key",
        "batch.storage.s3.secret-key",
        "batch.storage.s3.bucket",
        "batch.storage.s3.region",
        "batch.storage.s3.path-style-enabled",
        "batch.security.bypass-mode",
        "batch.security.kms.default-key-ref",
        "batch.security.kms.keys.DEFAULT_TEST",
        "batch.worker.drain.heartbeat-timeout-scheduler-enabled",
        "batch.partition-lease.reclaim-scheduler-enabled")) {
      String value = environment.getProperty(key);
      if (value != null) {
        properties.put(key, value);
      }
    }
    properties.put("server.port", 0);
    properties.put(
        "batch.orchestrator.base-url",
        "http://127.0.0.1:" + environment.getRequiredProperty("local.server.port"));
    properties.put("batch.storage.backend", "s3");
    properties.put("batch.storage.s3.endpoint", s3Endpoint());
    properties.put("batch.storage.s3.access-key", s3AccessKey());
    properties.put("batch.storage.s3.secret-key", s3SecretKey());
    properties.put("batch.storage.s3.bucket", s3Bucket());
    properties.put("batch.storage.s3.region", s3Region());
    properties.put("batch.storage.s3.path-style-enabled", true);
    properties.put("batch.worker.export.worker-type", "EXPORT");
    String[] arguments = properties.entrySet().stream()
        .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
        .toArray(String[]::new);
    return new SpringApplicationBuilder(E2eExportApplication.class)
        .profiles("test", "e2e")
        .run(arguments);
  }

  private ConfigurableApplicationContext startDispatchWorker() {
    Map<String, Object> properties = new LinkedHashMap<>();
    for (String key : List.of(
        "spring.datasource.url",
        "spring.datasource.username",
        "spring.datasource.password",
        "spring.datasource.driver-class-name",
        "spring.datasource.hikari.maximum-pool-size",
        "spring.datasource.hikari.minimum-idle",
        "spring.kafka.bootstrap-servers",
        "batch.datasource.business.url",
        "batch.datasource.business.username",
        "batch.datasource.business.password",
        "batch.datasource.business.schema",
        "spring.data.redis.host",
        "spring.data.redis.port",
        "batch.storage.backend",
        "batch.storage.filesystem.root",
        "batch.storage.filesystem.download-base-url",
        "batch.storage.filesystem.presign-secret",
        "batch.storage.s3.endpoint",
        "batch.storage.s3.access-key",
        "batch.storage.s3.secret-key",
        "batch.storage.s3.bucket",
        "batch.storage.s3.region",
        "batch.storage.s3.path-style-enabled",
        "batch.security.bypass-mode",
        "batch.security.kms.default-key-ref",
        "batch.security.kms.keys.DEFAULT_TEST",
        "batch.worker.drain.heartbeat-timeout-scheduler-enabled",
        "batch.partition-lease.reclaim-scheduler-enabled")) {
      String value = environment.getProperty(key);
      if (value != null) {
        properties.put(key, value);
      }
    }
    properties.put("server.port", 0);
    properties.put(
        "batch.orchestrator.base-url",
        "http://127.0.0.1:" + environment.getRequiredProperty("local.server.port"));
    properties.put("batch.worker.dispatch.worker-type", "DISPATCH");
    properties.put("batch.worker.dispatch.worker-code", "journey-dispatch-" + System.nanoTime());
    properties.put("batch.worker.dispatch.runtime.local-sandbox-root", dispatchTempDir.toString());
    String[] arguments = properties.entrySet().stream()
        .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
        .toArray(String[]::new);
    return new SpringApplicationBuilder(E2eDispatchApplication.class)
        .profiles("test", "e2e")
        .run(arguments);
  }

  private void awaitTaskSuccess(String dedupKey) {
    awaitTaskStatus(dedupKey, "SUCCESS");
  }

  private void awaitTaskStatus(String dedupKey, String expectedStatus) {
    final String selectTaskStatusSql = """
                      select t.task_status from batch.job_task t
                      join batch.job_instance ji on ji.id = t.job_instance_id
                      where ji.tenant_id = ? and ji.dedup_key = ?
                      order by t.id desc limit 1
                      """;

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          String status =
              jdbcTemplate.queryForObject(selectTaskStatusSql, String.class, TENANT, dedupKey);
          assertThat(status).isEqualTo(expectedStatus);
        });
  }
}
