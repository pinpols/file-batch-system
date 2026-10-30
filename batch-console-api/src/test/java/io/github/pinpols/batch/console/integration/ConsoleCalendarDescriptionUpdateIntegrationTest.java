package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.application.config.TenantConfigPackageExcelService;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigPackageExcelApplyRequest;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleRoles;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import io.github.pinpols.batch.console.support.excel.TenantConfigPackageExcelImportStore;
import io.github.pinpols.batch.console.support.excel.TenantConfigPackageExcelImportStore.PackageExcelSession;
import java.time.Instant;
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

/** 说明由配置导入写入，普通日历维护请求没有该字段，不应借缺键将其清空。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("日历维护：保留请求无法编辑的既有说明")
class ConsoleCalendarDescriptionUpdateIntegrationTest extends AbstractMutationIntegrationTest {

  private final TenantConfigPackageExcelService excel;
  private final TenantConfigPackageExcelImportStore sessions;

  @Autowired
  ConsoleCalendarDescriptionUpdateIntegrationTest(
      TenantConfigPackageExcelService excel, TenantConfigPackageExcelImportStore sessions) {
    this.excel = excel;
    this.sessions = sessions;
  }

  private ServletRequestAttributes requestContext;

  @BeforeEach
  void bindSeedRequestScope() {
    requestContext = new ServletRequestAttributes(new MockHttpServletRequest());
    RequestContextHolder.setRequestAttributes(requestContext);
  }

  @AfterEach
  void clearPrincipal() {
    requestContext.requestCompleted();
    RequestContextHolder.resetRequestAttributes();
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("Excel导入的日历说明在HTTP修改名称后仍保持，实际更新名称和说明均回读数据库")
  void shouldPreserveImportedDescription_whenUpdatingCalendarThroughHttp() {
    String suffix = Long.toUnsignedString(System.nanoTime());
    String tenant = "calendar-description-" + suffix;
    String code = "description_calendar_" + suffix;
    ConsolePrincipal principal =
        new ConsolePrincipal("calendar-description-it", tenant, Set.of(ConsoleRoles.ADMIN));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority(ConsoleRoles.ADMIN))));
    Map<String, String> calendar = Map.of(
        "tenant_id",
        tenant,
        "calendar_code",
        code,
        "calendar_name",
        "原日历",
        "timezone",
        "UTC",
        "description",
        "配置包中的既有说明");
    PackageExcelSession seed = PackageExcelSession.builder()
        .fileName("calendar-description.xlsx")
        .tenantId(tenant)
        .uploadedAt(Instant.now())
        .resourceQueueRows(List.of())
        .businessCalendarRows(List.of(calendar))
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
      assertThat(excel.apply(token, request).businessCalendarInserted()).isEqualTo(1);
    } finally {
      sessions.remove(token);
    }
    String selectIdSql =
        "select id from batch.business_calendar where tenant_id = ? and calendar_code = ?";
    Long id = jdbcTemplate.queryForObject(selectIdSql, Long.class, tenant, code);

    client
        .put()
        .uri("/api/console/calendars/" + id)
        .header(
            CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "calendar-description-update-" + suffix)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of(
            "tenantId", tenant, "calendarCode", code, "calendarName", "新日历", "timezone", "UTC"))
        .exchange()
        .expectStatus()
        .isOk();

    String readStateSql = """
        select calendar_name, description from batch.business_calendar
        where tenant_id = ? and id = ?
        """;
    assertThat(jdbcTemplate.queryForMap(readStateSql, tenant, id))
        .containsEntry("calendar_name", "新日历")
        .containsEntry("description", "配置包中的既有说明");
  }
}
