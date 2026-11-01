package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.application.config.ConsoleConfigSyncApplicationService;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncBundlePayload;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncImportRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.FileChannelSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.InitMode;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigSyncImportResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse.TenantInitResult;
import io.github.pinpols.batch.console.config.ConsoleSecurityConfiguration;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleAuthenticationFilter;
import io.github.pinpols.batch.console.support.maintenance.MaintenanceModeFilter;
import io.github.pinpols.batch.e2e.apps.E2eDispatchApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eOutboxPublishSupport;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@E2eSpringBootTest(
    classes = {E2eDispatchApplication.class, RuntimeConfigImportDispatchE2eIT.ConsoleConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.worker.dispatch.worker-type=DISPATCH",
      "batch.retry.poll-interval-millis=600000",
      "batch.outbox.poll-interval-millis=600000"
    })
@E2eBusinessSchema
@DisplayName("配置导入与在途派发任务的渠道快照边界")
class RuntimeConfigImportDispatchE2eIT extends AbstractIntegrationTest {

  @TestConfiguration(proxyBeanMethods = false)
  @Import(SecretPayloadProtector.class)
  @ComponentScan(
      basePackages = {
        "io.github.pinpols.batch.common.resilience",
        "io.github.pinpols.batch.console.application",
        "io.github.pinpols.batch.console.config",
        "io.github.pinpols.batch.console.domain",
        "io.github.pinpols.batch.console.infrastructure",
        "io.github.pinpols.batch.console.shared",
        "io.github.pinpols.batch.console.support",
        "io.github.pinpols.batch.console.service",
        "io.github.pinpols.batch.console.web"
      },
      excludeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = {
                ConsoleSecurityConfiguration.class,
                ConsoleAuthenticationFilter.class,
                MaintenanceModeFilter.class
              }))
  @MapperScan(
      basePackages = {
        "io.github.pinpols.batch.console.mapper",
        "io.github.pinpols.batch.console.domain.audit.mapper",
        "io.github.pinpols.batch.console.domain.file.mapper",
        "io.github.pinpols.batch.console.domain.governance.mapper",
        "io.github.pinpols.batch.console.domain.job.mapper",
        "io.github.pinpols.batch.console.domain.notification.mapper",
        "io.github.pinpols.batch.console.domain.observability.mapper",
        "io.github.pinpols.batch.console.domain.ops.mapper",
        "io.github.pinpols.batch.console.domain.rbac.mapper",
        "io.github.pinpols.batch.console.domain.workflow.mapper"
      },
      sqlSessionFactoryRef = "sqlSessionFactory",
      nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class)
  static class ConsoleConfig {}

  private static final String TENANT = "t1";

  private final WorkflowRunLaunchService launchService;

  private final ConsoleConfigSyncApplicationService syncService;

  private final E2eOutboxPublishSupport outbox;

  private final JdbcTemplate jdbc;

  @Autowired
  RuntimeConfigImportDispatchE2eIT(
      WorkflowRunLaunchService launchService,
      ConsoleConfigSyncApplicationService syncService,
      E2eOutboxPublishSupport outbox,
      JdbcTemplate jdbc) {
    this.launchService = launchService;
    this.syncService = syncService;
    this.outbox = outbox;
    this.jdbc = jdbc;
  }

  @TempDir
  private Path tempDir;

  @Test
  @DisplayName("任务创建后导入新渠道配置，Worker 仍按任务冻结的旧地址投递")
  void shouldDeliverToFrozenChannelAfterConfigImport() throws Exception {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String channelCode = "config-import-" + suffix;
    String requestId = "config-import-" + suffix;
    Path originalDirectory = tempDir.resolve("original");
    Path updatedDirectory = tempDir.resolve("updated");
    insertLocalChannel(channelCode, originalDirectory);
    Long fileId = insertGeneratedFile(suffix, requestId);
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbc, TENANT, "DISPATCH", "dispatch", TriggerType.API);
    Map<String, Object> params = Map.of(
        "fileId",
        fileId.toString(),
        "channelCode",
        channelCode,
        "externalRequestId",
        requestId,
        "ackRequired",
        false);
    LaunchRequest launch = new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        LocalDate.of(2026, 1, 15),
        TriggerType.API,
        seed.requestId(),
        "config-import-trace-" + suffix,
        params);
    launchService.launch(launch);

    FileChannelSpec changedChannel = channelSpec(channelCode, updatedDirectory);
    ConfigSyncBundlePayload bundle = new ConfigSyncBundlePayload();
    bundle.setFileChannels(List.of(changedChannel));
    ConfigSyncImportRequest request = new ConfigSyncImportRequest();
    request.setTenantId(TENANT);
    request.setSourceEnv("source-" + suffix);
    request.setTargetEnv("integration-test");
    request.setTargetTenantIds(List.of(TENANT));
    request.setMode(InitMode.UPSERT);
    request.setBundle(bundle);
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    try {
      ConfigSyncImportResponse response = syncService.importBundle(request);
      assertThat(response.result().failureTenants()).isZero();
      assertThat(response.result().results())
          .singleElement()
          .satisfies(result -> assertThat(result.fileChannels().updated()).isOne());
      String selectEndpoint = """
          select config_json ->> 'target_endpoint' from batch.file_channel_config
          where tenant_id = ? and channel_code = ?
          """;
      String storedEndpoint =
          jdbc.queryForObject(selectEndpoint, String.class, TENANT, channelCode);
      assertThat(storedEndpoint).isEqualTo(updatedDirectory.toString());
      String receiptSql = """
          select result_json::text from batch.config_sync_target_receipt
          where audit_tenant_id = ? and log_id = ? and tenant_id = ?
          """;
      String receiptJson =
          jdbc.queryForObject(receiptSql, String.class, TENANT, response.syncLogId(), TENANT);
      TenantInitResult receipt = JsonUtils.fromJsonStrict(receiptJson, TenantInitResult.class);
      assertThat(receipt.tenantId()).isEqualTo(TENANT);
      assertThat(receipt.success()).isTrue();
      assertThat(receipt.fileChannels().updated()).isOne();
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }

    outbox.publishAllPending(TENANT);
    String taskStatusSql = """
        select task.task_status from batch.job_task task
        join batch.job_instance instance on instance.tenant_id = task.tenant_id
          and instance.id = task.job_instance_id
        where instance.tenant_id = ? and instance.dedup_key = ?
        """;
    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          String status = jdbc.queryForObject(taskStatusSql, String.class, TENANT, seed.dedupKey());
          assertThat(status).isEqualTo("SUCCESS");
        });
    Path envelope = originalDirectory.resolve(channelCode + "-" + requestId + ".json");
    assertThat(envelope).isRegularFile();
    JsonNode payload =
        JsonUtils.fromJson(Files.readString(envelope), JsonNode.class).path("payload");
    assertThat(payload.path("fileId").asLong()).isEqualTo(fileId);
    assertThat(payload.path("channelCode").asText()).isEqualTo(channelCode);
    assertThat(updatedDirectory.resolve(envelope.getFileName())).doesNotExist();
  }

  private void insertLocalChannel(String channelCode, Path endpoint) {
    String insertSql = """
        insert into batch.file_channel_config (
            tenant_id, channel_code, channel_name, channel_type, target_endpoint, auth_type,
            config_json, receipt_policy, timeout_seconds, enabled
        ) values (?, ?, ?, 'LOCAL', null, 'NONE',
            jsonb_build_object('target_endpoint', ?, 'receipt_policy', 'NONE',
                'channel_type', 'LOCAL', 'channel_code', ?), 'NONE', 10, true)
        """;
    jdbc.update(insertSql, TENANT, channelCode, "本地派发渠道", endpoint.toString(), channelCode);
  }

  private Long insertGeneratedFile(String suffix, String traceId) {
    String insertSql = """
        insert into batch.file_record (
            tenant_id, file_code, biz_type, file_category, file_name, original_file_name, file_ext,
            file_format_type, charset, mime_type, file_size_bytes, checksum_type, storage_type,
            storage_path, source_type, file_status, biz_date, trace_id
        ) values (?, ?, 'OUTPUT', 'OUTPUT', ?, ?, 'json', 'JSON', 'UTF-8', 'application/json',
            32, 'NONE', 'LOCAL', ?, 'SYSTEM', 'GENERATED', date '2026-01-15', ?) returning id
        """;
    String name = "dispatch-" + suffix + ".json";
    return jdbc.queryForObject(
        insertSql,
        Long.class,
        TENANT,
        "file-" + suffix,
        name,
        name,
        tempDir.resolve(name).toString(),
        traceId);
  }

  private static FileChannelSpec channelSpec(String code, Path endpoint) {
    FileChannelSpec spec = new FileChannelSpec();
    spec.setChannelCode(code);
    spec.setChannelName("本地派发渠道");
    spec.setChannelType("LOCAL");
    spec.setTargetEndpoint(endpoint.toString());
    spec.setAuthType("NONE");
    spec.setConfigJson(JsonUtils.toJson(Map.of(
        "target_endpoint",
        endpoint.toString(),
        "receipt_policy",
        "NONE",
        "channel_type",
        "LOCAL",
        "channel_code",
        code)));
    spec.setReceiptPolicy("NONE");
    spec.setTimeoutSeconds(10);
    spec.setEnabled(true);
    return spec;
  }
}
