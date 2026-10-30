package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.application.config.ConfigReleaseApplyService;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import io.github.pinpols.batch.console.application.config.TenantConfigPackageExcelService;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.BatchWindowSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.BusinessCalendarSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.InitMode;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigPackageExcelApplyRequest;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse;
import io.github.pinpols.batch.console.domain.entity.ConfigReleaseEntity;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleRoles;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import io.github.pinpols.batch.console.support.excel.TenantConfigPackageExcelImportStore;
import io.github.pinpols.batch.console.support.excel.TenantConfigPackageExcelImportStore.PackageExcelSession;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** 真实应用服务、PG事务及Redis会话核对导入写入；Excel以已解析的上传会话作为输入，不代替工作簿上传验收。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("窗口导入约束：初始化严格/部分事务与Excel预览、应用保持同一规则")
class BatchWindowImportValidationIntegrationTest extends AbstractIntegrationTest {

  private final ConsoleTenantConfigInitApplicationService init;
  private final TenantConfigPackageExcelService excel;
  private final TenantConfigPackageExcelImportStore sessions;
  private final JdbcTemplate jdbc;
  private final ConfigReleaseApplyService releases;

  @Autowired
  BatchWindowImportValidationIntegrationTest(
      ConsoleTenantConfigInitApplicationService init,
      TenantConfigPackageExcelService excel,
      TenantConfigPackageExcelImportStore sessions,
      JdbcTemplate jdbc,
      ConfigReleaseApplyService releases) {
    this.init = init;
    this.excel = excel;
    this.sessions = sessions;
    this.jdbc = jdbc;
    this.releases = releases;
  }

  @AfterEach
  void clearPrincipal() {
    SecurityContextHolder.clearContext();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("无效时区和非跨日反向窗口按条目拒绝；严格回滚同租户，部分模式保留合法兄弟")
  void shouldRejectInvalidWindows_underStrictAndPartialTransactions(boolean strict) {
    String tenant = authorizeFreshTenant();
    BatchWindowSpec good = window("good_window", "UTC", "01:00", "02:00", false);
    BatchWindowSpec badZone = window("bad_zone", "Mars/Phobos", "01:00", "02:00", false);
    BatchWindowSpec badRange = window("bad_range", "UTC", "03:00", "02:00", false);
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(List.of(tenant));
    request.setMode(InitMode.UPSERT);
    request.setStrict(strict);
    request.setBatchWindows(List.of(good, badZone, badRange));

    TenantConfigBatchInitResponse response =
        init.batchInit(request, "window-import-it", operationKey());

    assertAll(
        () -> assertThat(response.failureTenants()).isEqualTo(strict ? 1 : 0),
        () -> assertThat(count(tenant, "bad_zone")).isZero(),
        () -> assertThat(count(tenant, "bad_range")).isZero(),
        () -> assertThat(count(tenant, "good_window")).isEqualTo(strict ? 0 : 1));
    if (!strict) {
      var stats = response.results().getFirst().batchWindows();
      assertThat(stats.created()).isEqualTo(1);
      assertThat(stats.failed()).isEqualTo(2);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"timezone", "range", "equal"})
  @DisplayName("非法Excel窗口在预览中成为错误行，应用拒绝且不写入窗口")
  void shouldRejectInvalidExcelWindow_inPreviewAndApply(String problem) {
    String tenant = authorizeFreshTenant();
    Map<String, String> row = excelRow(tenant, "invalid_window");
    switch (problem) {
      case "timezone" -> row.put("timezone", "Mars/Phobos");
      case "range" -> row.put("start_time", "03:00");
      case "equal" -> row.put("end_time", "01:00");
      default -> throw new IllegalArgumentException("unexpected fixture problem");
    }
    String token = sessions.save(session(tenant, row));
    try {
      var preview = excel.preview(token);
      TenantConfigPackageExcelApplyRequest request = new TenantConfigPackageExcelApplyRequest();
      request.setPreviewToken(preview.previewToken());
      Throwable failure = catchThrowable(() -> excel.apply(token, request));

      assertAll(
          () -> assertThat(preview.invalidRows()).isEqualTo(1),
          () -> assertThat(preview.errorRows())
              .anyMatch(error -> error.sheetName().equals("batch_window")),
          () -> assertThat(failure).isInstanceOf(BizException.class),
          () -> assertThat(count(tenant, "invalid_window")).isZero());
    } finally {
      sessions.remove(token);
    }
  }

  @Test
  @DisplayName("Excel省略跨日等可选列时，预览及应用保留目标已有值，合法跨日更新不被误拒")
  void shouldPreserveExistingCrossDay_whenExcelOmitsOptionalFlags() {
    String tenant = authorizeFreshTenant();
    BatchWindowSpec seed = window("overnight_window", "UTC", "22:00", "02:00", true);
    seed.setEndStrategy("CONTINUE");
    seed.setOutOfWindowAction("FAIL");
    seed.setEnabled(false);
    seed.setDescription("原说明");
    applyOne(tenant, seed);
    Map<String, String> row = excelRow(tenant, "overnight_window");
    row.put("start_time", "22:00");
    row.put("end_time", "02:00");
    row.remove("allow_cross_day");
    String token = sessions.save(session(tenant, row));
    try {
      var preview = excel.preview(token);
      assertThat(preview.invalidRows()).isZero();
      TenantConfigPackageExcelApplyRequest request = new TenantConfigPackageExcelApplyRequest();
      request.setPreviewToken(preview.previewToken());
      assertThat(excel.apply(token, request).batchWindowUpdated()).isEqualTo(1);
      assertThat(snapshot(tenant, "overnight_window"))
          .containsEntry("start_time", "22:00:00")
          .containsEntry("end_time", "02:00:00")
          .containsEntry("allow_cross_day", true)
          .containsEntry("end_strategy", "CONTINUE")
          .containsEntry("out_of_window_action", "FAIL")
          .containsEntry("enabled", false)
          .containsEntry("description", "原说明");
    } finally {
      sessions.remove(token);
    }
  }

  @Test
  @DisplayName("Excel显式关闭跨日时不能沿用旧true绕过约束，错误行应用不改已有窗口")
  void shouldRejectExplicitFalse_whenExistingWindowIsCrossDay() {
    String tenant = authorizeFreshTenant();
    applyOne(tenant, window("overnight_window", "UTC", "22:00", "02:00", true));
    Map<String, Object> before = snapshot(tenant, "overnight_window");
    Map<String, String> row = excelRow(tenant, "overnight_window");
    row.put("start_time", "22:00");
    row.put("end_time", "02:00");
    String token = sessions.save(session(tenant, row));
    try {
      var preview = excel.preview(token);
      assertThat(preview.invalidRows()).isEqualTo(1);
      TenantConfigPackageExcelApplyRequest request = new TenantConfigPackageExcelApplyRequest();
      request.setPreviewToken(preview.previewToken());
      assertThatThrownBy(() -> excel.apply(token, request)).isInstanceOf(BizException.class);
      assertThat(snapshot(tenant, "overnight_window")).containsExactlyInAnyOrderEntriesOf(before);
    } finally {
      sessions.remove(token);
    }
  }

  @Test
  @DisplayName("JSON初始化保留省略时区/跨日的原默认值，并允许显式跨日相等全天窗口")
  void shouldPreserveJsonDefaults_andExplicitFullDayWindow() {
    String tenant = authorizeFreshTenant();
    BatchWindowSpec ordinary = window("default_window", null, "01:00", "02:00", false);
    ordinary.setAllowCrossDay(null);
    applyOne(tenant, ordinary);
    assertThat(snapshot(tenant, "default_window"))
        .containsEntry("timezone", "Asia/Shanghai")
        .containsEntry("allow_cross_day", false)
        .containsEntry("end_strategy", "FINISH_RUNNING")
        .containsEntry("out_of_window_action", "WAIT")
        .containsEntry("enabled", true);
    applyOne(tenant, window("full_day", "UTC", "01:00", "01:00", true));
    assertThat(snapshot(tenant, "full_day"))
        .containsEntry("start_time", "01:00:00")
        .containsEntry("end_time", "01:00:00")
        .containsEntry("allow_cross_day", true);
  }

  @ParameterizedTest
  @ValueSource(strings = {"timezone", "range"})
  @DisplayName("JSON UPSERT非法覆盖按条目失败，保留目标原窗口与更新统计")
  void shouldKeepExistingWindow_whenJsonOverwriteIsInvalid(String problem) {
    String tenant = authorizeFreshTenant();
    applyOne(tenant, window("existing_window", "UTC", "01:00", "02:00", false));
    Map<String, Object> before = snapshot(tenant, "existing_window");
    BatchWindowSpec invalid = window("existing_window", "UTC", "01:00", "02:00", false);
    if (problem.equals("timezone")) {
      invalid.setTimezone("Mars/Phobos");
    } else {
      invalid.setStartTime("03:00");
    }
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(List.of(tenant));
    request.setMode(InitMode.UPSERT);
    request.setStrict(false);
    request.setBatchWindows(List.of(invalid));
    var response = init.batchInit(request, "window-import-it", operationKey());
    assertThat(response.failureTenants()).isZero();
    var stats = response.results().getFirst().batchWindows();
    assertThat(stats.updated()).isZero();
    assertThat(stats.failed()).isEqualTo(1);
    assertThat(snapshot(tenant, "existing_window")).containsExactlyInAnyOrderEntriesOf(before);
  }

  @Test
  @DisplayName("SKIP_EXISTING不应用非法输入，不因新增实际写入校验改变跳过语义")
  void shouldKeepSkipExistingSemantics_withoutApplyingUnusedSpec() {
    String tenant = authorizeFreshTenant();
    applyOne(tenant, window("existing_window", "UTC", "01:00", "02:00", false));
    Map<String, Object> before = snapshot(tenant, "existing_window");
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(List.of(tenant));
    request.setMode(InitMode.SKIP_EXISTING);
    request.setStrict(true);
    request.setBatchWindows(
        List.of(window("existing_window", "Mars/Phobos", "03:00", "02:00", false)));
    var response = init.batchInit(request, "window-import-it", operationKey());
    assertThat(response.failureTenants()).isZero();
    assertThat(response.results().getFirst().batchWindows().skipped()).isEqualTo(1);
    assertThat(snapshot(tenant, "existing_window")).containsExactlyInAnyOrderEntriesOf(before);
  }

  @Test
  @DisplayName("配置发布应用复用初始化写入约束，非法窗口不进入运行态表")
  void shouldRejectInvalidWindow_whenReleaseAppliesRuntimeConfig() {
    String tenant = authorizeFreshTenant();
    BatchWindowSpec invalid = window("release_window", "Mars/Phobos", "01:00", "02:00", false);
    ConfigReleaseEntity release = new ConfigReleaseEntity();
    release.setTenantId(tenant);
    release.setConfigType("BATCH_WINDOW");
    release.setConfigKey("release_window");
    release.setConfigPayload(JsonUtils.toJson(invalid));
    assertThatThrownBy(() -> releases.apply(release, "window-import-it", operationKey()))
        .isInstanceOfSatisfying(
            BizException.class,
            failure ->
                assertThat(failure.getMessageKey()).isEqualTo("error.config.release_apply_failed"));
    assertThat(count(tenant, "release_window")).isZero();
  }

  @Test
  @DisplayName("日历发布拒绝非法时区并保留原有合法配置")
  void shouldRejectInvalidCalendarReleaseWithoutChangingExistingCalendar() {
    String tenant = authorizeFreshTenant();
    BusinessCalendarSpec spec = new BusinessCalendarSpec();
    spec.setCalendarCode("release_calendar");
    spec.setCalendarName("合法日历");
    spec.setTimezone("America/New_York");
    ConfigReleaseEntity release = new ConfigReleaseEntity();
    release.setTenantId(tenant);
    release.setConfigType("BUSINESS_CALENDAR");
    release.setConfigKey("release_calendar");
    release.setConfigPayload(JsonUtils.toJson(spec));
    releases.apply(release, "calendar-release-it", operationKey());
    String selectSql = """
        select calendar_name, timezone from batch.business_calendar where tenant_id=? and calendar_code=?
        """;
    Map<String, Object> before = jdbc.queryForMap(selectSql, tenant, "release_calendar");
    assertThat(before)
        .containsEntry("calendar_name", "合法日历")
        .containsEntry("timezone", "America/New_York");
    spec.setCalendarName("无效变更");
    spec.setTimezone("Mars/Phobos");
    release.setConfigPayload(JsonUtils.toJson(spec));
    assertThatThrownBy(() -> releases.apply(release, "calendar-release-it", operationKey()))
        .isInstanceOfSatisfying(
            BizException.class,
            failure ->
                assertThat(failure.getMessageKey()).isEqualTo("error.config.release_apply_failed"));
    assertThat(jdbc.queryForMap(selectSql, tenant, "release_calendar")).isEqualTo(before);
  }

  @Test
  @DisplayName("Excel 日历无效时区在预览与应用中拒绝且不落库")
  void shouldRejectInvalidCalendarExcel_inPreviewAndApply() {
    String tenant = authorizeFreshTenant();
    Map<String, String> row = Map.of(
        "tenant_id",
        tenant,
        "calendar_code",
        "invalid_calendar",
        "calendar_name",
        "无效日历",
        "timezone",
        "Mars/Phobos");
    PackageExcelSession calendarSession = session(tenant, Map.of()).toBuilder()
        .businessCalendarRows(List.of(row))
        .batchWindowRows(List.of())
        .build();
    String token = sessions.save(calendarSession);
    try {
      var preview = excel.preview(token);
      TenantConfigPackageExcelApplyRequest request = new TenantConfigPackageExcelApplyRequest();
      request.setPreviewToken(preview.previewToken());
      Throwable failure = catchThrowable(() -> excel.apply(token, request));
      String countSql =
          "select count(*) from batch.business_calendar where tenant_id=? and calendar_code=?";
      Integer count = jdbc.queryForObject(countSql, Integer.class, tenant, "invalid_calendar");
      assertAll(
          () -> assertThat(preview.invalidRows()).isEqualTo(1),
          () -> assertThat(preview.errorRows())
              .anyMatch(error -> error.sheetName().equals("business_calendar")),
          () -> assertThat(failure).isInstanceOf(BizException.class),
          () -> assertThat(count).isZero());
    } finally {
      sessions.remove(token);
    }
  }

  private void applyOne(String tenant, BatchWindowSpec spec) {
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(List.of(tenant));
    request.setMode(InitMode.UPSERT);
    request.setStrict(true);
    request.setBatchWindows(List.of(spec));
    assertThat(init.batchInit(request, "window-import-it", operationKey()).failureTenants())
        .isZero();
  }

  private Map<String, Object> snapshot(String tenant, String code) {
    String sql = """
        select window_name, timezone, start_time::text as start_time, end_time::text as end_time,
          end_strategy, out_of_window_action, allow_cross_day, enabled, description
        from batch.batch_window where tenant_id = ? and window_code = ?
        """;
    return jdbc.queryForMap(sql, tenant, code);
  }

  private BatchWindowSpec window(
      String code, String timezone, String start, String end, boolean crossDay) {
    BatchWindowSpec spec = new BatchWindowSpec();
    spec.setWindowCode(code);
    spec.setWindowName("导入窗口");
    spec.setTimezone(timezone);
    spec.setStartTime(start);
    spec.setEndTime(end);
    spec.setAllowCrossDay(crossDay);
    return spec;
  }

  private Map<String, String> excelRow(String tenant, String code) {
    Map<String, String> row = new LinkedHashMap<>();
    row.put("tenant_id", tenant);
    row.put("window_code", code);
    row.put("window_name", "Excel窗口");
    row.put("timezone", "UTC");
    row.put("start_time", "01:00");
    row.put("end_time", "02:00");
    row.put("allow_cross_day", "false");
    return row;
  }

  private PackageExcelSession session(String tenant, Map<String, String> row) {
    return PackageExcelSession.builder()
        .fileName("window-validation.xlsx")
        .tenantId(tenant)
        .uploadedAt(Instant.now())
        .resourceQueueRows(List.of())
        .businessCalendarRows(List.of())
        .batchWindowRows(List.of(row))
        .jobRows(List.of())
        .jobMonitoringPolicyRows(List.of())
        .fileChannelRows(List.of())
        .fileTemplateRows(List.of())
        .pipelineRows(List.of())
        .pipelineStepRows(List.of())
        .workflowDefinitionRows(List.of())
        .workflowNodeRows(List.of())
        .workflowEdgeRows(List.of())
        .workflowMonitoringPolicyRows(List.of())
        .permissionScope(Set.of())
        .build();
  }

  private int count(String tenant, String code) {
    String sql = "select count(*) from batch.batch_window where tenant_id = ? and window_code = ?";
    return jdbc.queryForObject(sql, Integer.class, tenant, code);
  }

  private String authorizeFreshTenant() {
    String tenant = "window-import-" + Long.toUnsignedString(System.nanoTime());
    ConsolePrincipal principal =
        new ConsolePrincipal("window-import-it", tenant, Set.of(ConsoleRoles.ADMIN));
    SimpleGrantedAuthority authority = new SimpleGrantedAuthority(ConsoleRoles.ADMIN);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, List.of(authority)));
    return tenant;
  }

  private String operationKey() {
    return "window-import-" + Long.toUnsignedString(System.nanoTime());
  }
}
