package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.application.config.ConsoleConfigSyncApplicationService;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncBundlePayload;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncImportRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.AlertRoutingSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.BatchWindowSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.BusinessCalendarSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.FileChannelSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.FileTemplateSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.InitMode;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.JobDefinitionSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.ResourceQueueSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.TenantQuotaPolicySpec;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigSyncImportResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.request.JobDependencyRuleRequest;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("租户配置严格应用事务：PostgreSQL 原子回滚")
class TenantConfigBatchInitStrictAtomicityIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT_ID = "strict-config-atomicity-it";

  @Autowired
  private ConsoleTenantConfigInitApplicationService initService;

  @Autowired
  private ConsoleConfigSyncApplicationService configSyncService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void ensureTenantCalendar() {
    TestTenantCalendarFixtures.ensureDefaultCalendar(jdbcTemplate, TENANT_ID);
  }

  @Test
  @DisplayName("严格应用中一项 Job 配置失败时回滚同租户已成功的兄弟项")
  void shouldRollbackSiblingConfiguration_whenStrictBundleContainsInvalidJob() {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String invalidJobCode = "STRICT_BAD_" + suffix;
    String validJobCode = "STRICT_GOOD_" + suffix;
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(List.of(TENANT_ID));
    request.setStrict(true);
    request.setJobDefinitions(List.of(job(invalidJobCode, 300), job(validJobCode, 0)));

    TenantConfigBatchInitResponse response =
        initService.batchInit(request, "integration-test", "strict-atomicity");

    assertThat(response.failureTenants()).isEqualTo(1);
    assertThat(response.results()).singleElement().satisfies(result -> {
      assertThat(result.success()).isFalse();
      assertThat(result.errorMessage()).contains("strict bundle aborted");
    });
    assertThat(countJob(invalidJobCode)).isZero();
    assertThat(countJob(validJobCode)).isZero();
  }

  @Test
  @DisplayName("严格初始化按依赖拓扑应用 Job，允许下游排在上游之前")
  void shouldApplyJobDependenciesBeforeDownstream_whenBundleListsDownstreamFirst() {
    final String selectUpstreamJobCodeSql = """
        select upstream_job_code || ':' || biz_date_alignment from batch.job_dependency where tenant_id = ?\s\
        and downstream_job_code = ? order by upstream_job_code\
        """;

    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String upstreamJobCode = "BATCH_UPSTREAM_" + suffix;
    String secondUpstreamJobCode = "BATCH_UPSTREAM_2_" + suffix;
    String downstreamJobCode = "BATCH_DOWNSTREAM_" + suffix;
    JobDefinitionSpec downstream = job(downstreamJobCode, 0);
    downstream.setDependsOnJobCode(upstreamJobCode);
    downstream.setDependencyRules(List.of(
        new JobDependencyRuleRequest(upstreamJobCode, "PREVIOUS_BIZ_DAY"),
        new JobDependencyRuleRequest(secondUpstreamJobCode, "SAME_DAY")));

    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(List.of(TENANT_ID));
    request.setStrict(true);
    request.setJobDefinitions(
        List.of(downstream, job(secondUpstreamJobCode, 0), job(upstreamJobCode, 0)));

    TenantConfigBatchInitResponse response =
        initService.batchInit(request, "integration-test", "dependency-order");

    assertThat(response.successTenants()).isOne();
    assertThat(response.failureTenants()).isZero();
    assertThat(countJob(upstreamJobCode)).isOne();
    assertThat(countJob(secondUpstreamJobCode)).isOne();
    assertThat(countJob(downstreamJobCode)).isOne();
    assertThat(jdbcTemplate.queryForList(
            selectUpstreamJobCodeSql, String.class, TENANT_ID, downstreamJobCode))
        .containsExactlyInAnyOrder(
            upstreamJobCode + ":PREVIOUS_BIZ_DAY", secondUpstreamJobCode + ":SAME_DAY");
  }

  @Test
  @DisplayName("批量初始化以稳定应用错误拒绝缺失的 Job 上游")
  void shouldRejectUnknownJobDependencyWithStableApplicationError() {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String downstreamJobCode = "BATCH_UNKNOWN_UPSTREAM_" + suffix;
    JobDefinitionSpec downstream = job(downstreamJobCode, 0);
    downstream.setDependsOnJobCode("MISSING_UPSTREAM_" + suffix);

    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(List.of(TENANT_ID));
    request.setJobDefinitions(List.of(downstream));

    TenantConfigBatchInitResponse response =
        initService.batchInit(request, "integration-test", "dependency-guard");

    assertThat(response.results()).singleElement().satisfies(result -> {
      assertThat(result.jobDefinitions().failed()).isOne();
      assertThat(result.jobDefinitions().details()).singleElement().satisfies(detail -> {
        assertThat(detail.action()).isEqualTo("FAILED");
        assertThat(detail.errorMessage()).isEqualTo("error.common.invalid_argument");
      });
    });
    assertThat(countJob(downstreamJobCode)).isZero();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("多上游项失败遵守严格回滚及非严格部分成功契约且不留下部分依赖边")
  void shouldKeepMultiDependencyFailureAtomic(boolean strict) {
    final String selectCountSql = """
          select count(*) from batch.job_dependency
          where tenant_id = ? and downstream_job_code = ?
          """;

    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String upstreamCode = "MULTI_GOOD_" + suffix;
    String downstreamCode = "MULTI_BAD_" + suffix;
    JobDefinitionSpec downstream = job(downstreamCode, 0);
    downstream.setDependencyRules(List.of(
        new JobDependencyRuleRequest(upstreamCode, "PREVIOUS_BIZ_DAY"),
        new JobDependencyRuleRequest("MISSING_" + suffix, "SAME_DAY")));
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(List.of(TENANT_ID));
    request.setStrict(strict);
    request.setJobDefinitions(List.of(downstream, job(upstreamCode, 0)));

    TenantConfigBatchInitResponse response =
        initService.batchInit(request, "integration-test", "multi-dependency-atomicity");

    assertThat(response.failureTenants()).isEqualTo(strict ? 1 : 0);
    assertThat(countJob(upstreamCode)).isEqualTo(strict ? 0 : 1);
    assertThat(countJob(downstreamCode)).isZero();
    assertThat(
            jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT_ID, downstreamCode))
        .isZero();
    assertThat(response.results()).singleElement().satisfies(result -> {
      assertThat(result.success()).isEqualTo(!strict);
      if (!strict) {
        assertThat(result.jobDefinitions().created()).isOne();
        assertThat(result.jobDefinitions().failed()).isOne();
        assertThat(result.jobDefinitions().details())
            .filteredOn(detail -> "FAILED".equals(detail.action()))
            .singleElement()
            .satisfies(detail ->
                assertThat(detail.errorMessage()).isEqualTo("error.common.invalid_argument"));
      }
    });
  }

  @Test
  @DisplayName("Config Sync 单项依赖失败时保留稳定错误并持久化部分失败日志")
  void shouldPersistPartialFailure_whenConfigSyncJobDependencyIsMissing() {
    final String selectTotalItemsSql = """
        select total_items, success_items, failed_items, skipped_items, sync_status from batch.config_sync_log\s\
        where tenant_id = ? and source_env = ? and sync_direction = 'IMPORT'\
        """;

    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String downstreamJobCode = "CONFIG_SYNC_MISSING_UPSTREAM_" + suffix;
    String sourceEnv = "config-sync-source-" + suffix;
    JobDefinitionSpec downstream = job(downstreamJobCode, 0);
    downstream.setDependsOnJobCode("MISSING_UPSTREAM_" + suffix);

    ConfigSyncBundlePayload bundle = new ConfigSyncBundlePayload();
    bundle.setJobDefinitions(List.of(downstream));
    ConfigSyncImportRequest request = new ConfigSyncImportRequest();
    request.setTenantId(TENANT_ID);
    request.setSourceEnv(sourceEnv);
    request.setTargetEnv("integration-test");
    request.setTargetTenantIds(List.of(TENANT_ID));
    request.setBundle(bundle);

    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    try {
      ConfigSyncImportResponse response = configSyncService.importBundle(request);

      assertThat(response.result().results()).singleElement().satisfies(result -> {
        assertThat(result.success()).isTrue();
        assertThat(result.jobDefinitions().failed()).isOne();
        assertThat(result.jobDefinitions().details()).singleElement().satisfies(detail -> {
          assertThat(detail.action()).isEqualTo("FAILED");
          assertThat(detail.errorMessage()).isEqualTo("error.common.invalid_argument");
        });
      });
      assertThat(countJob(downstreamJobCode)).isZero();
      assertThat(jdbcTemplate.queryForMap(selectTotalItemsSql, TENANT_ID, sourceEnv))
          .containsEntry("total_items", 1)
          .containsEntry("success_items", 0)
          .containsEntry("failed_items", 1)
          .containsEntry("skipped_items", 0)
          .containsEntry("sync_status", "PARTIAL_FAILED");
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
  }

  @Test
  @DisplayName("UPSERT 已有文件渠道时完整替换并持久化连接与回执配置")
  void shouldPersistFileChannelFields_whenUpsertingExistingChannel() {
    final String selectChannelNameSql = """
      select channel_name, channel_type, target_endpoint, auth_type, config_json ->> 'user' as config_user,\s\
      config_json ->> 'secretRef' as secret_ref, receipt_policy, timeout_seconds, enabled\s\
      from batch.file_channel_config where tenant_id = ? and channel_code = ?\
      """;
    final String selectChannelNameSql2 = """
      select channel_name from batch.file_channel_config where tenant_id = ? and channel_code = ?
  """;

    String channelCode =
        "UPSERT_CH_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    TenantConfigBatchInitRequest initialRequest = requestWithChannel(channelCode);
    TenantConfigBatchInitResponse initialResponse =
        initService.batchInit(initialRequest, "integration-test", "channel-initial");

    assertThat(initialResponse.successTenants()).isEqualTo(1);
    assertThat(countChannel(channelCode)).isOne();

    TenantConfigBatchInitRequest upsertRequest = requestWithChannel(channelCode);
    upsertRequest.setMode(InitMode.UPSERT);
    FileChannelSpec channel = upsertRequest.getFileChannels().getFirst();
    channel.setChannelName("Updated SFTP channel");
    channel.setChannelType("SFTP");
    channel.setTargetEndpoint("sftp://files.example.test/inbound-v2");
    channel.setAuthType("PASSWORD");
    channel.setConfigJson("{\"user\":\"batch-v2\",\"secretRef\":\"vault/channel-v2\"}");
    channel.setReceiptPolicy("POLLING");
    channel.setTimeoutSeconds(75);
    channel.setEnabled(false);

    TenantConfigBatchInitResponse upsertResponse =
        initService.batchInit(upsertRequest, "integration-test", "channel-upsert");

    assertThat(upsertResponse.successTenants()).isEqualTo(1);
    assertThat(upsertResponse.results())
        .singleElement()
        .satisfies(result -> assertThat(result.fileChannels().updated()).isEqualTo(1));
    assertThat(countChannel(channelCode)).isOne();
    var persisted = jdbcTemplate.queryForMap(selectChannelNameSql, TENANT_ID, channelCode);
    assertThat(persisted)
        .containsEntry("channel_name", "Updated SFTP channel")
        .containsEntry("channel_type", "SFTP")
        .containsEntry("target_endpoint", "sftp://files.example.test/inbound-v2")
        .containsEntry("auth_type", "PASSWORD")
        .containsEntry("config_user", "batch-v2")
        .containsEntry("secret_ref", "vault/channel-v2")
        .containsEntry("receipt_policy", "POLLING")
        .containsEntry("timeout_seconds", 75)
        .containsEntry("enabled", false);

    TenantConfigBatchInitRequest skipRequest = requestWithChannel(channelCode);
    skipRequest.getFileChannels().getFirst().setChannelName("Must remain skipped");
    TenantConfigBatchInitResponse skipResponse =
        initService.batchInit(skipRequest, "integration-test", "channel-skip");
    assertThat(skipResponse.results())
        .singleElement()
        .satisfies(result -> assertThat(result.fileChannels().skipped()).isEqualTo(1));
    assertThat(jdbcTemplate.queryForObject(
            selectChannelNameSql2, String.class, TENANT_ID, channelCode))
        .isEqualTo("Updated SFTP channel");
  }

  @Test
  @DisplayName("UPSERT 业务日历时替换日历字段及关联假日")
  void shouldReplaceBusinessCalendarAndHolidays_whenUpsertingExistingCalendar() {
    final String selectCalendarNameSql = """
      select calendar_name, timezone, holiday_roll_rule, catch_up_policy, catch_up_max_days, enabled\s\
      from batch.business_calendar where tenant_id = ? and calendar_code = ? and enabled = true\
      """;
    final String selectCalendarNameSql2 = """
      select calendar_name from batch.business_calendar where tenant_id = ? and calendar_code = ?
  """;

    String calendarCode =
        "upsert_cal_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    TenantConfigBatchInitRequest initialRequest = requestWithCalendar(
        calendarCode, "Initial calendar", "UTC", "SKIP", "NONE", 0, List.of("2026-12-25"));
    TenantConfigBatchInitResponse initialResponse =
        initService.batchInit(initialRequest, "integration-test", "calendar-initial");

    assertThat(initialResponse.successTenants()).isEqualTo(1);
    assertThat(countCalendar(calendarCode)).isOne();
    assertThat(holidayDates(calendarCode)).containsExactly("2026-12-25");

    TenantConfigBatchInitRequest upsertRequest = requestWithCalendar(
        calendarCode,
        "Updated calendar",
        "America/New_York",
        "NEXT_WORKDAY",
        "AUTO",
        3,
        List.of("2026-12-26", "2026-12-27"));
    upsertRequest.setMode(InitMode.UPSERT);
    TenantConfigBatchInitResponse upsertResponse =
        initService.batchInit(upsertRequest, "integration-test", "calendar-upsert");

    assertThat(upsertResponse.successTenants()).isEqualTo(1);
    assertThat(upsertResponse.results())
        .singleElement()
        .satisfies(result -> assertThat(result.businessCalendars().updated()).isEqualTo(1));
    assertThat(countCalendar(calendarCode)).isOne();
    var persisted = jdbcTemplate.queryForMap(selectCalendarNameSql, TENANT_ID, calendarCode);
    assertThat(persisted)
        .containsEntry("calendar_name", "Updated calendar")
        .containsEntry("timezone", "America/New_York")
        .containsEntry("holiday_roll_rule", "NEXT_WORKDAY")
        .containsEntry("catch_up_policy", "AUTO")
        .containsEntry("catch_up_max_days", 3)
        .containsEntry("enabled", true);
    assertThat(holidayDates(calendarCode)).containsExactly("2026-12-26", "2026-12-27");

    TenantConfigBatchInitRequest skipRequest = requestWithCalendar(
        calendarCode, "Must remain skipped", "UTC", "SKIP", "NONE", 0, List.of("2026-12-28"));
    TenantConfigBatchInitResponse skipResponse =
        initService.batchInit(skipRequest, "integration-test", "calendar-skip");
    assertThat(skipResponse.results())
        .singleElement()
        .satisfies(result -> assertThat(result.businessCalendars().skipped()).isEqualTo(1));
    assertThat(jdbcTemplate.queryForObject(
            selectCalendarNameSql2, String.class, TENANT_ID, calendarCode))
        .isEqualTo("Updated calendar");
    assertThat(holidayDates(calendarCode)).containsExactly("2026-12-26", "2026-12-27");
  }

  @Test
  @DisplayName("UPSERT 运维配置时完整持久化资源队列、批次窗口、配额和告警路由")
  void shouldPersistOperationalConfigFields_whenUpsertingExistingConfigs() {
    final String selectQueueNameSql = """
        select queue_name, max_running_jobs, max_running_partitions, max_qps, worker_group, resource_tag,\s\
        priority_policy, fair_share_weight, enabled from batch.resource_queue where tenant_id = ?\s\
        and queue_code = ?\
        """;
    final String selectWindowNameSql = """
      select window_name, timezone, start_time::text as start_time, end_time::text as end_time, end_strategy,\s\
      out_of_window_action, allow_cross_day, enabled from batch.batch_window where tenant_id = ?\s\
      and window_code = ?\
      """;
    final String selectMaxRunningJobsPerTenantSql = """
      select max_running_jobs_per_tenant, max_partitions_per_tenant, max_qps_per_tenant, fair_share_weight,\s\
      enabled, description from batch.tenant_quota_policy where tenant_id = ? and policy_code = ?\
      """;
    final String selectRouteNameSql = """
      select route_name, team, alert_group, severity, receiver, group_by, group_wait_seconds,\s\
      group_interval_seconds, repeat_interval_seconds, enabled from batch.alert_routing_config\s\
      where tenant_id = ? and route_code = ?\
      """;
    final String selectQueueNameSql2 = """
      select queue_name from batch.resource_queue where tenant_id = ? and queue_code = ?
  """;
    final String selectWindowNameSql2 = """
      select window_name from batch.batch_window where tenant_id = ? and window_code = ?
  """;
    final String selectDescriptionSql = """
        select description from batch.tenant_quota_policy where tenant_id = ? and policy_code = ?
    """;
    final String selectReceiverSql = """
        select receiver from batch.alert_routing_config where tenant_id = ? and route_code = ?
    """;

    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String queueCode = "upsert_queue_" + suffix;
    String windowCode = "upsert_window_" + suffix;
    String policyCode = "upsert_quota_" + suffix;
    String routeCode = "upsert_route_" + suffix;
    TenantConfigBatchInitRequest initialRequest =
        requestWithOperationalConfigs(queueCode, windowCode, policyCode, routeCode);
    TenantConfigBatchInitResponse initialResponse =
        initService.batchInit(initialRequest, "integration-test", "ops-initial");

    assertThat(initialResponse.successTenants()).isEqualTo(1);
    assertThat(countConfig("batch.resource_queue", "queue_code", queueCode)).isOne();
    assertThat(countConfig("batch.batch_window", "window_code", windowCode)).isOne();
    assertThat(countConfig("batch.tenant_quota_policy", "policy_code", policyCode))
        .isOne();
    assertThat(countConfig("batch.alert_routing_config", "route_code", routeCode))
        .isOne();

    TenantConfigBatchInitRequest upsertRequest =
        requestWithOperationalConfigs(queueCode, windowCode, policyCode, routeCode);
    upsertRequest.setMode(InitMode.UPSERT);
    ResourceQueueSpec queue = upsertRequest.getResourceQueues().getFirst();
    queue.setQueueName("Updated queue");
    queue.setQueueType("EXPORT");
    queue.setMaxRunningJobs(11);
    queue.setMaxRunningPartitions(22);
    queue.setMaxQps(33);
    queue.setWorkerGroup("EXPORTER");
    queue.setResourceTag("gold-v2");
    queue.setPriorityPolicy("FAIR_SHARE");
    queue.setFairShareWeight(7);
    queue.setEnabled(false);
    queue.setDescription("updated queue");
    BatchWindowSpec window = upsertRequest.getBatchWindows().getFirst();
    window.setWindowName("Updated window");
    window.setTimezone("America/New_York");
    window.setStartTime("03:15");
    window.setEndTime("06:45");
    window.setEndStrategy("CONTINUE");
    window.setOutOfWindowAction("FAIL");
    window.setAllowCrossDay(true);
    window.setEnabled(false);
    window.setDescription("updated window");
    TenantQuotaPolicySpec quota = upsertRequest.getQuotaPolicies().getFirst();
    quota.setMaxRunningJobsPerTenant(41);
    quota.setMaxPartitionsPerTenant(42);
    quota.setMaxQpsPerTenant(43);
    quota.setFairShareWeight(9);
    quota.setEnabled(false);
    quota.setDescription("updated quota");
    AlertRoutingSpec route = upsertRequest.getAlertRoutings().getFirst();
    route.setRouteName("Updated route");
    route.setTeam("payments-v2");
    route.setAlertGroup("daily-import-v2");
    route.setSeverity("ERROR");
    route.setReceiver("oncall-v2@example.test");
    route.setGroupBy("tenant,workflow");
    route.setGroupWaitSeconds(41);
    route.setGroupIntervalSeconds(42);
    route.setRepeatIntervalSeconds(43);
    route.setEnabled(false);
    route.setDescription("updated route");

    TenantConfigBatchInitResponse upsertResponse =
        initService.batchInit(upsertRequest, "integration-test", "ops-upsert");

    assertThat(upsertResponse.successTenants()).isEqualTo(1);
    assertThat(upsertResponse.results()).singleElement().satisfies(result -> {
      assertThat(result.resourceQueues().updated()).isEqualTo(1);
      assertThat(result.batchWindows().updated()).isEqualTo(1);
      assertThat(result.quotaPolicies().updated()).isEqualTo(1);
      assertThat(result.alertRoutings().updated()).isEqualTo(1);
    });
    assertThat(countConfig("batch.resource_queue", "queue_code", queueCode)).isOne();
    assertThat(countConfig("batch.batch_window", "window_code", windowCode)).isOne();
    assertThat(countConfig("batch.tenant_quota_policy", "policy_code", policyCode))
        .isOne();
    assertThat(countConfig("batch.alert_routing_config", "route_code", routeCode))
        .isOne();
    assertThat(jdbcTemplate.queryForMap(selectQueueNameSql, TENANT_ID, queueCode))
        .containsEntry("queue_name", "Updated queue")
        .containsEntry("max_running_jobs", 11)
        .containsEntry("max_running_partitions", 22)
        .containsEntry("max_qps", 33)
        .containsEntry("worker_group", "EXPORTER")
        .containsEntry("resource_tag", "gold-v2")
        .containsEntry("priority_policy", "FAIR_SHARE")
        .containsEntry("fair_share_weight", 7)
        .containsEntry("enabled", false);
    assertThat(jdbcTemplate.queryForMap(selectWindowNameSql, TENANT_ID, windowCode))
        .containsEntry("window_name", "Updated window")
        .containsEntry("timezone", "America/New_York")
        .containsEntry("start_time", "03:15:00")
        .containsEntry("end_time", "06:45:00")
        .containsEntry("end_strategy", "CONTINUE")
        .containsEntry("out_of_window_action", "FAIL")
        .containsEntry("allow_cross_day", true)
        .containsEntry("enabled", false);
    assertThat(jdbcTemplate.queryForMap(selectMaxRunningJobsPerTenantSql, TENANT_ID, policyCode))
        .containsEntry("max_running_jobs_per_tenant", 41)
        .containsEntry("max_partitions_per_tenant", 42)
        .containsEntry("max_qps_per_tenant", 43)
        .containsEntry("fair_share_weight", 9)
        .containsEntry("enabled", false)
        .containsEntry("description", "updated quota");
    assertThat(jdbcTemplate.queryForMap(selectRouteNameSql, TENANT_ID, routeCode))
        .containsEntry("route_name", "Updated route")
        .containsEntry("team", "payments-v2")
        .containsEntry("alert_group", "daily-import-v2")
        .containsEntry("severity", "ERROR")
        .containsEntry("receiver", "oncall-v2@example.test")
        .containsEntry("group_by", "tenant,workflow")
        .containsEntry("group_wait_seconds", 41)
        .containsEntry("group_interval_seconds", 42)
        .containsEntry("repeat_interval_seconds", 43)
        .containsEntry("enabled", false);

    TenantConfigBatchInitResponse skipResponse = initService.batchInit(
        requestWithOperationalConfigs(queueCode, windowCode, policyCode, routeCode),
        "integration-test",
        "ops-skip");
    assertThat(skipResponse.results()).singleElement().satisfies(result -> {
      assertThat(result.resourceQueues().skipped()).isEqualTo(1);
      assertThat(result.batchWindows().skipped()).isEqualTo(1);
      assertThat(result.quotaPolicies().skipped()).isEqualTo(1);
      assertThat(result.alertRoutings().skipped()).isEqualTo(1);
    });
    assertThat(jdbcTemplate.queryForObject(selectQueueNameSql2, String.class, TENANT_ID, queueCode))
        .isEqualTo("Updated queue");
    assertThat(
            jdbcTemplate.queryForObject(selectWindowNameSql2, String.class, TENANT_ID, windowCode))
        .isEqualTo("Updated window");
    assertThat(
            jdbcTemplate.queryForObject(selectDescriptionSql, String.class, TENANT_ID, policyCode))
        .isEqualTo("updated quota");
    assertThat(jdbcTemplate.queryForObject(selectReceiverSql, String.class, TENANT_ID, routeCode))
        .isEqualTo("oncall-v2@example.test");
  }

  @Test
  @DisplayName("UPSERT 已有文件模板时完整替换并持久化格式、运行时和安全配置")
  void shouldPersistFileTemplateFields_whenUpsertingExistingTemplate() {
    final String selectTemplateNameSql = """
      select template_name, biz_type, file_format_type, charset, target_charset, with_bom, line_separator,\s\
      delimiter, quote_char, escape_char, record_length, header_rows, footer_rows, header_template ->> 'marker'\s\
      as header_marker, trailer_template ->> 'marker' as trailer_marker, checksum_type, compress_type,\s\
      encrypt_type, naming_rule, field_mappings ->> 'marker' as mapping_marker, validation_rule_set ->> 'marker'\s\
      as validation_marker, default_query_code, default_query_sql, query_param_schema ->> 'marker' as\s\
      query_schema_marker, streaming_enabled, page_size, fetch_size, chunk_size, preview_masking_enabled,\s\
      error_line_masking_enabled, log_masking_enabled, content_encryption_enabled, encryption_key_ref,\s\
      download_requires_approval, masking_rule_set, enabled, version, description\s\
      from batch.file_template_config where tenant_id = ? and template_code = ? and version = 1\s\
      and is_deleted = false\
      """;
    final String selectTemplateNameSql2 = """
      select template_name from batch.file_template_config where tenant_id = ? and template_code = ?\s\
      and version = 1 and is_deleted = false\
      """;

    String templateCode =
        "UPSERT_TPL_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    TenantConfigBatchInitRequest initialRequest = requestWithTemplate(templateCode);
    TenantConfigBatchInitResponse initialResponse =
        initService.batchInit(initialRequest, "integration-test", "template-initial");

    assertThat(initialResponse.successTenants()).isEqualTo(1);
    assertThat(countTemplate(templateCode)).isOne();

    TenantConfigBatchInitRequest upsertRequest = requestWithTemplate(templateCode);
    upsertRequest.setMode(InitMode.UPSERT);
    upsertRequest.getFileTemplates().getFirst().setTemplateName("Updated template");
    upsertRequest.getFileTemplates().getFirst().setBizType("CUSTOMER_V2");
    upsertRequest.getFileTemplates().getFirst().setFileFormatType("DELIMITED");
    upsertRequest.getFileTemplates().getFirst().setCharset("UTF-8");
    upsertRequest.getFileTemplates().getFirst().setTargetCharset("UTF-16LE");
    upsertRequest.getFileTemplates().getFirst().setWithBom(true);
    upsertRequest.getFileTemplates().getFirst().setLineSeparator("CRLF");
    upsertRequest.getFileTemplates().getFirst().setDelimiter("|");
    upsertRequest.getFileTemplates().getFirst().setQuoteChar("'");
    upsertRequest.getFileTemplates().getFirst().setEscapeChar("\\");
    upsertRequest.getFileTemplates().getFirst().setRecordLength(512);
    upsertRequest.getFileTemplates().getFirst().setHeaderRows(2);
    upsertRequest.getFileTemplates().getFirst().setFooterRows(1);
    upsertRequest.getFileTemplates().getFirst().setHeaderTemplateJson("{\"marker\":\"updated\"}");
    upsertRequest.getFileTemplates().getFirst().setTrailerTemplateJson("{\"marker\":\"updated\"}");
    upsertRequest.getFileTemplates().getFirst().setChecksumType("SHA-256");
    upsertRequest.getFileTemplates().getFirst().setCompressType("GZIP");
    upsertRequest.getFileTemplates().getFirst().setEncryptType("AES");
    upsertRequest.getFileTemplates().getFirst().setNamingRule("customer-${bizDate}-v2");
    upsertRequest.getFileTemplates().getFirst().setFieldMappingsJson("{\"marker\":\"updated\"}");
    upsertRequest
        .getFileTemplates()
        .getFirst()
        .setValidationRuleSetJson("{\"marker\":\"updated\"}");
    upsertRequest.getFileTemplates().getFirst().setDefaultQueryCode("query-v2");
    upsertRequest.getFileTemplates().getFirst().setDefaultQuerySql("select 2");
    upsertRequest.getFileTemplates().getFirst().setQueryParamSchemaJson("{\"marker\":\"updated\"}");
    upsertRequest.getFileTemplates().getFirst().setStreamingEnabled(true);
    upsertRequest.getFileTemplates().getFirst().setPageSize(101);
    upsertRequest.getFileTemplates().getFirst().setFetchSize(202);
    upsertRequest.getFileTemplates().getFirst().setChunkSize(303);
    upsertRequest.getFileTemplates().getFirst().setPreviewMaskingEnabled(true);
    upsertRequest.getFileTemplates().getFirst().setErrorLineMaskingEnabled(false);
    upsertRequest.getFileTemplates().getFirst().setLogMaskingEnabled(true);
    upsertRequest.getFileTemplates().getFirst().setContentEncryptionEnabled(true);
    upsertRequest.getFileTemplates().getFirst().setEncryptionKeyRef("kms/customer-v2");
    upsertRequest.getFileTemplates().getFirst().setDownloadRequiresApproval(true);
    upsertRequest.getFileTemplates().getFirst().setMaskingRuleSet("PCI_BASIC_V2");
    upsertRequest.getFileTemplates().getFirst().setEnabled(false);
    upsertRequest.getFileTemplates().getFirst().setDescription("updated description");

    TenantConfigBatchInitResponse upsertResponse =
        initService.batchInit(upsertRequest, "integration-test", "template-upsert");

    assertThat(upsertResponse.successTenants()).isEqualTo(1);
    assertThat(upsertResponse.results())
        .singleElement()
        .satisfies(result -> assertThat(result.fileTemplates().updated()).isEqualTo(1));
    assertThat(countTemplate(templateCode)).isOne();
    var persisted = jdbcTemplate.queryForMap(selectTemplateNameSql, TENANT_ID, templateCode);
    assertThat(persisted)
        .containsEntry("template_name", "Updated template")
        .containsEntry("biz_type", "CUSTOMER_V2")
        .containsEntry("file_format_type", "DELIMITED")
        .containsEntry("charset", "UTF-8")
        .containsEntry("target_charset", "UTF-16LE")
        .containsEntry("with_bom", true)
        .containsEntry("line_separator", "CRLF")
        .containsEntry("delimiter", "|")
        .containsEntry("quote_char", "'")
        .containsEntry("escape_char", "\\")
        .containsEntry("record_length", 512)
        .containsEntry("header_rows", 2)
        .containsEntry("footer_rows", 1)
        .containsEntry("header_marker", "updated")
        .containsEntry("trailer_marker", "updated")
        .containsEntry("checksum_type", "SHA-256")
        .containsEntry("compress_type", "GZIP")
        .containsEntry("encrypt_type", "AES")
        .containsEntry("naming_rule", "customer-${bizDate}-v2")
        .containsEntry("mapping_marker", "updated")
        .containsEntry("validation_marker", "updated")
        .containsEntry("default_query_code", "query-v2")
        .containsEntry("default_query_sql", "select 2")
        .containsEntry("query_schema_marker", "updated")
        .containsEntry("streaming_enabled", true)
        .containsEntry("page_size", 101)
        .containsEntry("fetch_size", 202)
        .containsEntry("chunk_size", 303)
        .containsEntry("preview_masking_enabled", true)
        .containsEntry("error_line_masking_enabled", false)
        .containsEntry("log_masking_enabled", true)
        .containsEntry("content_encryption_enabled", true)
        .containsEntry("encryption_key_ref", "kms/customer-v2")
        .containsEntry("download_requires_approval", true)
        .containsEntry("masking_rule_set", "PCI_BASIC_V2")
        .containsEntry("enabled", false)
        .containsEntry("version", 1)
        .containsEntry("description", "updated description");

    TenantConfigBatchInitRequest skipRequest = requestWithTemplate(templateCode);
    skipRequest.getFileTemplates().getFirst().setTemplateName("Must remain skipped");
    TenantConfigBatchInitResponse skipResponse =
        initService.batchInit(skipRequest, "integration-test", "template-skip");
    assertThat(skipResponse.results())
        .singleElement()
        .satisfies(result -> assertThat(result.fileTemplates().skipped()).isEqualTo(1));
    assertThat(jdbcTemplate.queryForObject(
            selectTemplateNameSql2, String.class, TENANT_ID, templateCode))
        .isEqualTo("Updated template");
  }

  private TenantConfigBatchInitRequest requestWithTemplate(String templateCode) {
    FileTemplateSpec template = new FileTemplateSpec();
    template.setTemplateCode(templateCode);
    template.setTemplateName("Initial template");
    template.setTemplateType("IMPORT");
    template.setFileFormatType("DELIMITED");
    template.setCharset("UTF-8");
    template.setWithBom(false);
    template.setRecordLength(0);
    template.setHeaderRows(1);
    template.setFooterRows(0);
    template.setChecksumType("NONE");
    template.setCompressType("NONE");
    template.setEncryptType("NONE");
    template.setStreamingEnabled(true);
    template.setPageSize(1000);
    template.setFetchSize(1000);
    template.setChunkSize(500);
    template.setPreviewMaskingEnabled(false);
    template.setErrorLineMaskingEnabled(false);
    template.setLogMaskingEnabled(false);
    template.setContentEncryptionEnabled(false);
    template.setDownloadRequiresApproval(false);
    template.setVersion(1);
    template.setEnabled(true);
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(List.of(TENANT_ID));
    request.setFileTemplates(List.of(template));
    return request;
  }

  private TenantConfigBatchInitRequest requestWithChannel(String channelCode) {
    FileChannelSpec channel = new FileChannelSpec();
    channel.setChannelCode(channelCode);
    channel.setChannelName("Initial SFTP channel");
    channel.setChannelType("SFTP");
    channel.setTargetEndpoint("sftp://files.example.test/inbound");
    channel.setAuthType("TOKEN");
    channel.setConfigJson("{\"user\":\"batch-v1\",\"secretRef\":\"vault/channel-v1\"}");
    channel.setReceiptPolicy("SYNC");
    channel.setTimeoutSeconds(30);
    channel.setEnabled(true);
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(List.of(TENANT_ID));
    request.setFileChannels(List.of(channel));
    return request;
  }

  private TenantConfigBatchInitRequest requestWithCalendar(
      String calendarCode,
      String calendarName,
      String timezone,
      String holidayRollRule,
      String catchUpPolicy,
      int catchUpMaxDays,
      List<String> holidays) {
    BusinessCalendarSpec calendar = new BusinessCalendarSpec();
    calendar.setCalendarCode(calendarCode);
    calendar.setCalendarName(calendarName);
    calendar.setTimezone(timezone);
    calendar.setHolidayRollRule(holidayRollRule);
    calendar.setCatchUpPolicy(catchUpPolicy);
    calendar.setCatchUpMaxDays(catchUpMaxDays);
    calendar.setEnabled(true);
    calendar.setHolidays(holidays);
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(List.of(TENANT_ID));
    request.setBusinessCalendars(List.of(calendar));
    return request;
  }

  private TenantConfigBatchInitRequest requestWithOperationalConfigs(
      String queueCode, String windowCode, String policyCode, String routeCode) {
    ResourceQueueSpec queue = new ResourceQueueSpec();
    queue.setQueueCode(queueCode);
    queue.setQueueName("Initial queue");
    queue.setQueueType("MIXED");
    queue.setMaxRunningJobs(1);
    queue.setMaxRunningPartitions(2);
    queue.setMaxQps(3);
    queue.setWorkerGroup("IMPORTER");
    queue.setResourceTag("silver");
    queue.setPriorityPolicy("FIFO");
    queue.setFairShareWeight(1);
    queue.setEnabled(true);
    queue.setDescription("initial queue");

    BatchWindowSpec window = new BatchWindowSpec();
    window.setWindowCode(windowCode);
    window.setWindowName("Initial window");
    window.setTimezone("UTC");
    window.setStartTime("01:00");
    window.setEndTime("02:00");
    window.setEndStrategy("FINISH_RUNNING");
    window.setOutOfWindowAction("WAIT");
    window.setAllowCrossDay(false);
    window.setEnabled(true);
    window.setDescription("initial window");

    TenantQuotaPolicySpec quota = new TenantQuotaPolicySpec();
    quota.setPolicyCode(policyCode);
    quota.setMaxRunningJobsPerTenant(1);
    quota.setMaxPartitionsPerTenant(2);
    quota.setMaxQpsPerTenant(3);
    quota.setFairShareWeight(1);
    quota.setEnabled(true);
    quota.setDescription("initial quota");

    AlertRoutingSpec route = new AlertRoutingSpec();
    route.setRouteCode(routeCode);
    route.setRouteName("Initial route");
    route.setTeam("payments");
    route.setAlertGroup("daily-import");
    route.setSeverity("WARN");
    route.setReceiver("oncall@example.test");
    route.setGroupBy("tenant");
    route.setGroupWaitSeconds(10);
    route.setGroupIntervalSeconds(20);
    route.setRepeatIntervalSeconds(30);
    route.setEnabled(true);
    route.setDescription("initial route");

    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(List.of(TENANT_ID));
    request.setResourceQueues(List.of(queue));
    request.setBatchWindows(List.of(window));
    request.setQuotaPolicies(List.of(quota));
    request.setAlertRoutings(List.of(route));
    return request;
  }

  private static JobDefinitionSpec job(String jobCode, int startGraceSeconds) {
    JobDefinitionSpec spec = new JobDefinitionSpec();
    spec.setJobCode(jobCode);
    spec.setJobName(jobCode);
    spec.setJobType("IMPORT");
    spec.setScheduleType("MANUAL");
    spec.setStartGraceSeconds(startGraceSeconds);
    spec.setEnabled(true);
    return spec;
  }

  private int countJob(String jobCode) {
    final String selectCountSql = """
        select count(*) from batch.job_definition where tenant_id = ? and job_code = ?
    """;

    return jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT_ID, jobCode);
  }

  private int countTemplate(String templateCode) {
    final String selectCountSql = """
        select count(*) from batch.file_template_config where tenant_id = ? and template_code = ?\s\
        and is_deleted = false\
        """;

    return jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT_ID, templateCode);
  }

  private int countChannel(String channelCode) {
    final String selectCountSql = """
        select count(*) from batch.file_channel_config where tenant_id = ? and channel_code = ?\
        """;

    return jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT_ID, channelCode);
  }

  private int countCalendar(String calendarCode) {
    final String selectCountSql = """
        select count(*) from batch.business_calendar where tenant_id = ? and calendar_code = ?\
        """;

    return jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT_ID, calendarCode);
  }

  private int countConfig(String tableName, String codeColumn, String code) {
    final String selectCountSql = """
      select count(*) from %s where tenant_id = ? and %s = ?\
      """.formatted(tableName, codeColumn);
    return jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT_ID, code);
  }

  private List<String> holidayDates(String calendarCode) {
    final String selectBizDateSql = """
        select h.biz_date::text from batch.calendar_holiday h\s\
        join batch.business_calendar c on c.id = h.calendar_id and c.tenant_id = h.tenant_id\s\
        where h.tenant_id = ? and c.calendar_code = ? order by h.biz_date\
        """;

    return jdbcTemplate.queryForList(selectBizDateSql, String.class, TENANT_ID, calendarCode);
  }
}
