package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.application.config.ConsoleConfigSyncApplicationService;
import io.github.pinpols.batch.console.application.config.TenantConfigPackageExcelService;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncBundlePayload;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncImportRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.FileChannelSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.InitMode;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigPackageExcelApplyRequest;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigSyncImportResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse.TenantInitResult;
import io.github.pinpols.batch.console.config.ConsoleSecurityConfiguration;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleAuthenticationFilter;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import io.github.pinpols.batch.console.support.maintenance.MaintenanceModeFilter;
import io.github.pinpols.batch.console.support.web.ConsoleRequestContextFilter;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.e2e.apps.E2eDispatchApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eOutboxPublishSupport;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.domain.query.WorkflowMonitoringQuery;
import io.github.pinpols.batch.orchestrator.domain.view.WorkflowMonitoringAlertCandidate;
import io.github.pinpols.batch.orchestrator.infrastructure.sla.WorkflowMonitoringAlertEmissionService;
import io.github.pinpols.batch.orchestrator.infrastructure.sla.WorkflowMonitoringCandidateService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@E2eSpringBootTest(
    classes = {E2eDispatchApplication.class, RuntimeConfigImportDispatchE2eIT.ConsoleConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.worker.dispatch.worker-type=DISPATCH",
      "batch.retry.poll-interval-millis=600000",
      "batch.outbox.poll-interval-millis=600000",
      "batch.sla.workflow-monitoring.enabled=false",
      "batch.alert.am-emit.enabled=false"
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
        "io.github.pinpols.batch.console.support"
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
      basePackages = "io.github.pinpols.batch.console.domain.**.mapper",
      sqlSessionFactoryRef = "sqlSessionFactory",
      nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class)
  static class ConsoleConfig {}

  private static final String TENANT = "t1";
  // 真实启动链会结算历史业务日；取一次 UTC 次日作为未截止业务日，与其他 E2E 约定一致。
  private static final LocalDate BIZ_DATE = LocalDate.now(ZoneOffset.UTC).plusDays(1);

  @Autowired
  private WorkflowRunLaunchService launchService;

  @Autowired
  private ConsoleConfigSyncApplicationService syncService;

  @Autowired
  private E2eOutboxPublishSupport outbox;

  @Autowired
  private JdbcTemplate jdbc;

  private final TenantConfigPackageExcelService excelService;
  private final WorkflowMonitoringCandidateService workflowMonitoring;
  private final WorkflowMonitoringAlertEmissionService workflowAlertEmission;
  private final BatchTimezoneProvider timezoneProvider;

  @Autowired
  RuntimeConfigImportDispatchE2eIT(
      TenantConfigPackageExcelService excelService,
      WorkflowMonitoringCandidateService workflowMonitoring,
      WorkflowMonitoringAlertEmissionService workflowAlertEmission,
      BatchTimezoneProvider timezoneProvider) {
    this.excelService = excelService;
    this.workflowMonitoring = workflowMonitoring;
    this.workflowAlertEmission = workflowAlertEmission;
    this.timezoneProvider = timezoneProvider;
  }

  @TempDir
  private Path tempDir;

  @Test
  @DisplayName("配置包导入的工作流三类时限策略被编排监控实际读取")
  void shouldReadImportedWorkflowPolicyWhenEvaluatingRuntime() throws Exception {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbc, TENANT, "DISPATCH", "dispatch", TriggerType.API, BIZ_DATE);
    String insertRunSql = """
        insert into batch.workflow_run (tenant_id, workflow_definition_id, biz_date,
          run_status, trace_id, started_at)
        values (?, ?, ?, 'RUNNING', ?, current_timestamp - interval '20 minutes') returning id
        """;
    Long runId = jdbc.queryForObject(
        insertRunSql,
        Long.class,
        TENANT,
        seed.workflowDefinitionId(),
        BIZ_DATE,
        "policy-runtime-" + suffix);
    WorkflowMonitoringQuery query = WorkflowMonitoringQuery.builder()
        .tenantId(TENANT)
        .resourceKey("workflow_run:" + runId)
        .limit(10)
        .build();
    assertThat(workflowMonitoring.select(query)).isEmpty();
    String insertOpportunitySql = """
        insert into batch.expected_work_occurrence (
          tenant_id, calendar_code, biz_date, workflow_definition_id, source_type,
          source_key, expected_at, due_at, occurrence_status
        ) values (?, 'e2e_calendar', (current_timestamp at time zone 'UTC')::date - 3,
                  ?, 'EXTERNAL_EVENT', ?, current_timestamp - interval '3 days',
                  current_timestamp - interval '3 days', 'EXPECTED') returning id
        """;
    Long occurrenceId = jdbc.queryForObject(
        insertOpportunitySql, Long.class, TENANT, seed.workflowDefinitionId(), "policy-" + suffix);
    WorkflowMonitoringQuery opportunityQuery = WorkflowMonitoringQuery.builder()
        .tenantId(TENANT)
        .resourceKey("expected_work_occurrence:" + occurrenceId)
        .limit(10)
        .build();
    assertThat(workflowMonitoring.select(opportunityQuery)).isEmpty();

    byte[] workbook;
    try (XSSFWorkbook excel = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = excel.createSheet("workflow_monitoring_policy");
      Row header = sheet.createRow(0);
      Row row = sheet.createRow(1);
      List<String> keys = List.of(
          "workflow_code",
          "workflow_version",
          "soft_runtime_seconds",
          "soft_runtime_severity",
          "start_grace_seconds",
          "start_grace_severity",
          "completion_deadline_local_time",
          "completion_deadline_day_offset",
          "completion_deadline_severity");
      List<String> values =
          List.of(seed.jobCode(), "1", "600", "ERROR", "120", "CRITICAL", "04:30", "1", "WARN");
      for (int index = 0; index < keys.size(); index++) {
        header.createCell(index).setCellValue(keys.get(index));
        row.createCell(index).setCellValue(values.get(index));
      }
      excel.write(output);
      workbook = output.toByteArray();
    }
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(
        ConsoleRequestContextFilter.REQUEST_METADATA_ATTRIBUTE,
        new ConsoleRequestMetadata(
            "request-" + suffix, "trace-" + suffix, TENANT, "test-admin", null, "127.0.0.1"));
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    ConsolePrincipal principal = new ConsolePrincipal("test-admin", TENANT, Set.of("ROLE_ADMIN"));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            principal, "test", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    try {
      MockMultipartFile file = new MockMultipartFile(
          "file",
          "policy.xlsx",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          workbook);
      var upload = excelService.upload(file, TENANT);
      var preview = excelService.preview(upload.uploadToken());
      assertThat(preview.invalidRows()).isZero();
      TenantConfigPackageExcelApplyRequest applyRequest =
          new TenantConfigPackageExcelApplyRequest();
      applyRequest.setPreviewToken(preview.previewToken());
      var applied = excelService.apply(upload.uploadToken(), applyRequest);
      assertThat(applied.workflowMonitoringPoliciesApplied()).isEqualTo(1);
    } finally {
      SecurityContextHolder.clearContext();
      RequestContextHolder.resetRequestAttributes();
    }

    List<WorkflowMonitoringAlertCandidate> candidates = workflowMonitoring.select(query);
    assertThat(candidates).singleElement().satisfies(candidate -> {
      assertThat(candidate.workflowDefinitionId()).isEqualTo(seed.workflowDefinitionId());
      assertThat(candidate.workflowRunId()).isEqualTo(runId);
      assertThat(candidate.violation())
          .isEqualTo(WorkflowMonitoringAlertCandidate.Violation.RUNNING_TOO_LONG);
      assertThat(candidate.severity()).isEqualTo("ERROR");
      assertThat(Duration.between(candidate.startedAt(), candidate.monitoringDeadlineAt()))
          .isEqualTo(Duration.ofSeconds(600));
    });
    WorkflowMonitoringQuery startQuery = WorkflowMonitoringQuery.builder()
        .tenantId(TENANT)
        .resourceKey("expected_work_occurrence:" + occurrenceId)
        .violation(WorkflowMonitoringAlertCandidate.Violation.NOT_STARTED)
        .limit(10)
        .build();
    WorkflowMonitoringQuery completionQuery = WorkflowMonitoringQuery.builder()
        .tenantId(TENANT)
        .resourceKey("expected_work_occurrence:" + occurrenceId)
        .violation(WorkflowMonitoringAlertCandidate.Violation.NOT_COMPLETED_BY_DEADLINE)
        .limit(10)
        .build();
    String selectTimezoneSql =
        "select timezone from batch.workflow_definition where tenant_id = ? and id = ?";
    String timezone =
        jdbc.queryForObject(selectTimezoneSql, String.class, TENANT, seed.workflowDefinitionId());
    assertThat(timezone).isNull();
    List<WorkflowMonitoringAlertCandidate> startCandidates = workflowMonitoring.select(startQuery);
    assertThat(startCandidates).singleElement().satisfies(candidate -> {
      assertThat(candidate.occurrenceId()).isEqualTo(occurrenceId);
      assertThat(candidate.severity()).isEqualTo("CRITICAL");
      assertThat(Duration.between(candidate.expectedAt(), candidate.monitoringDeadlineAt()))
          .isEqualTo(Duration.ofSeconds(120));
    });
    List<WorkflowMonitoringAlertCandidate> completionCandidates =
        workflowMonitoring.select(completionQuery);
    assertThat(completionCandidates).singleElement().satisfies(candidate -> {
      assertThat(candidate.occurrenceId()).isEqualTo(occurrenceId);
      assertThat(candidate.severity()).isEqualTo("WARN");
      ZoneId zone = timezoneProvider.defaultZone();
      var localDate = candidate.expectedAt().atZone(zone).toLocalDate();
      var expectedDeadline =
          localDate.plusDays(1).atTime(LocalTime.of(4, 30)).atZone(zone).toInstant();
      assertThat(candidate.monitoringDeadlineAt()).isEqualTo(expectedDeadline);
    });

    for (WorkflowMonitoringAlertCandidate candidate :
        List.of(candidates.get(0), startCandidates.get(0), completionCandidates.get(0))) {
      assertThat(workflowAlertEmission.emitOnce(candidate)).isTrue();
      assertThat(workflowAlertEmission.emitOnce(candidate)).isFalse();
    }
    String alertSql = """
        select alert_type, severity, status, occurrence_count,
               detail_json ->> 'monitoringResourceKey' as resource_key
        from batch.alert_event
        where tenant_id = ? and detail_json ->> 'workflowDefinitionId' = ?
        """;
    List<String> alerts = jdbc.query(
        alertSql,
        (row, index) -> String.join(
            "|",
            row.getString("alert_type"),
            row.getString("severity"),
            row.getString("status"),
            row.getString("occurrence_count"),
            row.getString("resource_key")),
        TENANT,
        seed.workflowDefinitionId().toString());
    assertThat(alerts)
        .containsExactlyInAnyOrder(
            "WORKFLOW_RUNNING_TOO_LONG|ERROR|OPEN|1|workflow_run:" + runId,
            "WORKFLOW_NOT_STARTED_BY_DEADLINE|CRITICAL|OPEN|1|expected_work_occurrence:"
                + occurrenceId,
            "WORKFLOW_NOT_COMPLETED_BY_DEADLINE|WARN|OPEN|1|expected_work_occurrence:"
                + occurrenceId);
  }

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
        jdbc, TENANT, "DISPATCH", "dispatch", TriggerType.API, BIZ_DATE);
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
        BIZ_DATE,
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
            32, 'NONE', 'LOCAL', ?, 'SYSTEM', 'GENERATED', ?, ?) returning id
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
        BIZ_DATE,
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
