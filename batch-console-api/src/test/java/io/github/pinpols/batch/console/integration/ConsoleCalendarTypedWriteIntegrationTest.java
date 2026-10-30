package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import io.github.pinpols.batch.console.application.config.TenantConfigPackageExcelService;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.BusinessCalendarSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.InitMode;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigPackageExcelApplyRequest;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.CalendarHolidayMapper;
import io.github.pinpols.batch.console.domain.job.param.BusinessCalendarInsertParam;
import io.github.pinpols.batch.console.domain.job.param.BusinessCalendarUpdateParam;
import io.github.pinpols.batch.console.domain.job.param.CalendarHolidayBatchInsertParam;
import io.github.pinpols.batch.console.domain.job.param.CalendarHolidayBatchUpsertParam;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleRoles;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import io.github.pinpols.batch.console.support.excel.TenantConfigPackageExcelImportStore;
import io.github.pinpols.batch.console.support.excel.TenantConfigPackageExcelImportStore.PackageExcelSession;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 固定参数具名化后的真实SQL绑定、HTTP归属及导入事务；直接Mapper仅准备配置与验证持久层契约。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("业务日历具名写入：主键、默认值、日期类型及归属边界")
class ConsoleCalendarTypedWriteIntegrationTest extends AbstractMutationIntegrationTest {
  private final BusinessCalendarMapper calendars;
  private final CalendarHolidayMapper holidays;
  private final ConsoleTenantConfigInitApplicationService init;
  private final TenantConfigPackageExcelService excel;
  private final TenantConfigPackageExcelImportStore sessions;
  private ServletRequestAttributes requestContext;

  @Autowired
  ConsoleCalendarTypedWriteIntegrationTest(
      BusinessCalendarMapper calendars,
      CalendarHolidayMapper holidays,
      ConsoleTenantConfigInitApplicationService init,
      TenantConfigPackageExcelService excel,
      TenantConfigPackageExcelImportStore sessions) {
    this.calendars = calendars;
    this.holidays = holidays;
    this.init = init;
    this.excel = excel;
    this.sessions = sessions;
  }

  @BeforeEach
  void bindRequestScope() {
    requestContext = new ServletRequestAttributes(new MockHttpServletRequest());
    RequestContextHolder.setRequestAttributes(requestContext);
  }

  @AfterEach
  void clearRequestScope() {
    requestContext.requestCompleted();
    RequestContextHolder.resetRequestAttributes();
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("创建SQL的空字符串及null默认分支、生成主键与更新租户谓词保留")
  void shouldBindDefaultsAndGeneratedId_andFenceCalendarUpdateByTenant() {
    String tenant = freshTenant();
    BusinessCalendarInsertParam input = BusinessCalendarInsertParam.builder()
        .tenantId(tenant)
        .calendarCode("defaults")
        .calendarName("原日历")
        .timezone("UTC")
        .holidayRollRule("")
        .catchUpPolicy("")
        .description("保留的说明")
        .build();
    assertThat(calendars.insert(input)).isEqualTo(1);
    assertThat(input.getId()).isPositive();
    var saved = calendars.selectById(tenant, input.getId());
    assertThat(saved.holidayRollRule()).isEqualTo("SKIP");
    assertThat(saved.catchUpPolicy()).isEqualTo("NONE");
    assertThat(saved.catchUpMaxDays()).isZero();
    assertThat(saved.enabled()).isTrue();
    assertThat(saved.description()).isEqualTo("保留的说明");
    BusinessCalendarUpdateParam wrongTenant = BusinessCalendarUpdateParam.builder()
        .id(input.getId())
        .tenantId(tenant + "-other")
        .calendarName("不应写入")
        .timezone("UTC")
        .holidayRollRule("NEXT_WORKDAY")
        .catchUpPolicy("AUTO")
        .catchUpMaxDays(4)
        .description("不应覆盖")
        .build();
    assertThat(calendars.update(wrongTenant)).isZero();
    assertThat(calendars.selectById(tenant, input.getId()).calendarName()).isEqualTo("原日历");
    assertThat(calendars.selectById(tenant + "-other", input.getId())).isNull();
  }

  @Test
  @DisplayName("HTTP创建默认禁用，更新字段正常落库且不会借保存接口切换启用状态")
  void shouldPreserveApiDefaultsAndUpdateFields_withoutTogglingCalendar() {
    String tenant = freshTenant();
    client
        .post()
        .uri("/api/console/calendars")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, operationKey())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of(
            "tenantId",
            tenant,
            "calendarCode",
            "http_calendar",
            "calendarName",
            "原名",
            "timezone",
            "UTC"))
        .exchange()
        .expectStatus()
        .isOk();
    var saved = calendars.selectByTenantAndCalendarCode(tenant, "http_calendar");
    assertThat(saved.id()).isPositive();
    assertThat(saved.enabled()).isFalse();
    assertThat(saved.description()).isNull();
    client
        .put()
        .uri("/api/console/calendars/" + saved.id())
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, operationKey())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of(
            "tenantId",
            tenant,
            "calendarCode",
            "http_calendar",
            "calendarName",
            "新名",
            "timezone",
            "Asia/Shanghai",
            "holidayRollRule",
            "PREV_WORKDAY",
            "catchUpPolicy",
            "MANUAL_APPROVAL",
            "catchUpMaxDays",
            7,
            "enabled",
            true))
        .exchange()
        .expectStatus()
        .isOk();
    var updated = calendars.selectById(tenant, saved.id());
    assertThat(updated.calendarCode()).isEqualTo("http_calendar");
    assertThat(updated.calendarName()).isEqualTo("新名");
    assertThat(updated.timezone()).isEqualTo("Asia/Shanghai");
    assertThat(updated.holidayRollRule()).isEqualTo("PREV_WORKDAY");
    assertThat(updated.catchUpPolicy()).isEqualTo("MANUAL_APPROVAL");
    assertThat(updated.catchUpMaxDays()).isEqualTo(7);
    assertThat(updated.enabled()).isFalse();
  }

  @Test
  @DisplayName("字符串批量日期与LocalDate冲突更新均绑定正确，冲突保留原主键且隔离另一父日历")
  void shouldBindStringAndLocalDateHolidayBatches_withoutChangingConflictIdentity() {
    String tenant = freshTenant();
    Long id = calendar(tenant, "date_types");
    Long sibling = calendar(tenant, "date_sibling");
    holidays.batchInsert(List.of(
        holiday(tenant, id, "2026-01-01"),
        holiday(tenant, id, "2026-02-17"),
        holiday(tenant, sibling, "2026-01-01")));
    Long originalId = holidays.selectByCalendarId(id).getFirst().id();
    CalendarHolidayBatchUpsertParam changed = CalendarHolidayBatchUpsertParam.builder()
        .tenantId(tenant)
        .calendarId(id)
        .bizDate(LocalDate.of(2026, 1, 1))
        .dayType("WORKDAY_OVERRIDE")
        .holidayName("调班")
        .description("变更说明")
        .build();
    CalendarHolidayBatchUpsertParam added = CalendarHolidayBatchUpsertParam.builder()
        .tenantId(tenant)
        .calendarId(id)
        .bizDate(LocalDate.of(2026, 3, 1))
        .dayType("HOLIDAY")
        .build();
    holidays.upsertBatch(List.of(changed, added));
    var rows = holidays.selectByCalendarId(id);
    assertThat(rows)
        .extracting(row -> row.bizDate())
        .containsExactly(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 17), LocalDate.of(2026, 3, 1));
    assertThat(rows.getFirst().id()).isEqualTo(originalId);
    assertThat(rows.getFirst().dayType()).isEqualTo("WORKDAY_OVERRIDE");
    assertThat(rows.getFirst().holidayName()).isEqualTo("调班");
    assertThat(rows.getFirst().description()).isEqualTo("变更说明");
    assertThat(rows.getLast().holidayName()).isNull();
    assertThat(rows.getLast().description()).isNull();
    assertThat(holidays.selectByCalendarId(sibling).getFirst().dayType()).isEqualTo("HOLIDAY");
    String tenantSql =
        "select distinct tenant_id from batch.calendar_holiday where calendar_id = ?";
    assertThat(jdbcTemplate.queryForList(tenantSql, String.class, id)).containsExactly(tenant);
  }

  @Test
  @DisplayName("HTTP节假日更新绑定完整字段，错误父日历或租户返回404且不改原行")
  void shouldUpdateHolidayFields_andRejectWrongParentOrTenant() {
    String tenant = freshTenant();
    Long id = calendar(tenant, "holiday_update");
    Long sibling = calendar(tenant, "holiday_other");
    holidays.batchInsert(List.of(holiday(tenant, id, "2026-01-01")));
    Long holidayId = holidays.selectByCalendarId(id).getFirst().id();
    Map<String, Object> request = Map.of(
        "tenantId",
        tenant,
        "bizDate",
        "2026-01-02",
        "dayType",
        "WORKDAY_OVERRIDE",
        "holidayName",
        "调班日",
        "description",
        "更新说明");
    client
        .put()
        .uri("/api/console/calendars/" + id + "/holidays/" + holidayId)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, operationKey())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .isOk();
    var updated = holidays.selectByCalendarIdAndId(id, holidayId);
    assertThat(updated.bizDate()).isEqualTo(LocalDate.of(2026, 1, 2));
    assertThat(updated.dayType()).isEqualTo("WORKDAY_OVERRIDE");
    assertThat(updated.holidayName()).isEqualTo("调班日");
    assertThat(updated.description()).isEqualTo("更新说明");
    client
        .put()
        .uri("/api/console/calendars/" + sibling + "/holidays/" + holidayId)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, operationKey())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .isNotFound();
    client
        .put()
        .uri("/api/console/calendars/" + id + "/holidays/" + holidayId)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, operationKey())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            Map.of("tenantId", tenant + "-other", "bizDate", "2026-01-03", "dayType", "HOLIDAY"))
        .exchange()
        .expectStatus()
        .isNotFound();
    assertThat(holidays.selectByCalendarIdAndId(id, holidayId)).isEqualTo(updated);
  }

  @Test
  @DisplayName("JSON节假日整体替换保留顺序和父主键；无效日期导致本条更新、删除及插入一同回滚")
  void shouldReplaceJsonHolidays_andRollbackFailedReplacement() {
    String tenant = freshTenant();
    assertThat(applyCalendar(tenant, "原名", List.of("2026-01-01", "2026-02-17"))
            .results()
            .getFirst()
            .businessCalendars()
            .created())
        .isEqualTo(1);
    Long id = calendars.selectByTenantAndCalendarCode(tenant, "json_calendar").id();
    assertThat(applyCalendar(tenant, "改名", List.of("2026-03-01"))
            .results()
            .getFirst()
            .businessCalendars()
            .updated())
        .isEqualTo(1);
    var before = holidays.selectByCalendarId(id);
    assertThat(before).extracting(row -> row.bizDate()).containsExactly(LocalDate.of(2026, 3, 1));
    assertThat(applyCalendar(tenant, "不应落库", List.of("not-a-date"))
            .results()
            .getFirst()
            .businessCalendars()
            .failed())
        .isEqualTo(1);
    assertThat(calendars.selectByTenantAndCalendarCode(tenant, "json_calendar").id())
        .isEqualTo(id);
    assertThat(calendars.selectById(tenant, id).calendarName()).isEqualTo("改名");
    assertThat(holidays.selectByCalendarId(id)).isEqualTo(before);
  }

  @Test
  @DisplayName("Excel解析日期经真实预览及应用：更新同日期、保留原主键及未提交的日期")
  void shouldApplyExcelLocalDates_usingExistingConflictKeys() {
    String tenant = freshTenant();
    Long id = calendar(tenant, "excel_calendar");
    holidays.batchInsert(
        List.of(holiday(tenant, id, "2026-01-01"), holiday(tenant, id, "2026-02-17")));
    Long originalId = holidays.selectByCalendarId(id).getFirst().id();
    Map<String, String> row = Map.of(
        "tenant_id",
        tenant,
        "calendar_code",
        "excel_calendar",
        "calendar_name",
        "Excel更新",
        "timezone",
        "UTC",
        "description",
        "Excel说明",
        "holidays",
        "2026-01-01,2026-03-01");
    PackageExcelSession seed = PackageExcelSession.builder()
        .fileName("calendar-typed.xlsx")
        .tenantId(tenant)
        .uploadedAt(Instant.now())
        .resourceQueueRows(List.of())
        .businessCalendarRows(List.of(row))
        .batchWindowRows(List.of())
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
    String token = sessions.save(seed);
    try {
      var preview = excel.preview(token);
      assertThat(preview.invalidRows()).isZero();
      TenantConfigPackageExcelApplyRequest request = new TenantConfigPackageExcelApplyRequest();
      request.setPreviewToken(preview.previewToken());
      assertThat(excel.apply(token, request).businessCalendarUpdated()).isEqualTo(1);
    } finally {
      sessions.remove(token);
    }
    var rows = holidays.selectByCalendarId(id);
    assertThat(rows)
        .extracting(rowValue -> rowValue.bizDate())
        .containsExactly(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 17), LocalDate.of(2026, 3, 1));
    assertThat(rows.getFirst().id()).isEqualTo(originalId);
    assertThat(rows.getFirst().description()).isEqualTo("Excel说明");
    assertThat(rows.get(1).description()).isNull();
    assertThat(rows.getLast().description()).isEqualTo("Excel说明");
    assertThat(calendars.selectById(tenant, id).calendarName()).isEqualTo("Excel更新");
  }

  private Long calendar(String tenant, String code) {
    BusinessCalendarInsertParam input = BusinessCalendarInsertParam.builder()
        .tenantId(tenant)
        .calendarCode(code)
        .calendarName(code)
        .timezone("UTC")
        .enabled(true)
        .build();
    assertThat(calendars.insert(input)).isEqualTo(1);
    assertThat(input.getId()).isPositive();
    return input.getId();
  }

  private static CalendarHolidayBatchInsertParam holiday(
      String tenant, Long calendarId, String date) {
    return CalendarHolidayBatchInsertParam.builder()
        .tenantId(tenant)
        .calendarId(calendarId)
        .bizDate(date)
        .dayType("HOLIDAY")
        .build();
  }

  private TenantConfigBatchInitResponse applyCalendar(
      String tenant, String name, List<String> dates) {
    BusinessCalendarSpec spec = new BusinessCalendarSpec();
    spec.setCalendarCode("json_calendar");
    spec.setCalendarName(name);
    spec.setTimezone("UTC");
    spec.setEnabled(true);
    spec.setHolidays(dates);
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(List.of(tenant));
    request.setMode(InitMode.UPSERT);
    request.setStrict(false);
    request.setBusinessCalendars(List.of(spec));
    return init.batchInit(request, "calendar-typed-it", operationKey());
  }

  private String freshTenant() {
    String tenant = "calendar-typed-" + Long.toUnsignedString(System.nanoTime());
    ConsolePrincipal principal =
        new ConsolePrincipal("calendar-typed-it", tenant, Set.of(ConsoleRoles.ADMIN));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority(ConsoleRoles.ADMIN))));
    return tenant;
  }

  private static String operationKey() {
    return "calendar-typed-" + Long.toUnsignedString(System.nanoTime());
  }
}
