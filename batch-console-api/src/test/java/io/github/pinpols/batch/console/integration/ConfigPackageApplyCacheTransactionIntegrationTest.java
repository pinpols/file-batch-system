package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.application.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.application.config.TenantConfigPackageExcelService;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigPackageExcelApplyRequest;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleRoles;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import io.github.pinpols.batch.console.support.excel.TenantConfigPackageExcelImportStore;
import io.github.pinpols.batch.console.support.excel.TenantConfigPackageExcelImportStore.PackageExcelSession;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 配置包实际 Apply 事务提交/回滚与 Redis 失效回调的联合验证。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("Excel Apply 真实事务与缓存失效")
class ConfigPackageApplyCacheTransactionIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private TenantConfigPackageExcelService service;

  @Autowired
  private TenantConfigPackageExcelImportStore sessions;

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private StringRedisTemplate redis;

  @MockitoSpyBean
  private ConsoleConfigCacheInvalidationService invalidation;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("Apply 仅在提交后清除缓存；回调登记后异常回滚数据库且保留缓存和可重试会话")
  void shouldAlignExcelApplyTransactionAndCache(boolean rollback) {
    String tenant = "excel-cache-" + UUID.randomUUID().toString().substring(0, 8);
    TestTenantCalendarFixtures.ensureDefaultCalendar(jdbc, tenant);
    ConsolePrincipal principal =
        new ConsolePrincipal("config-cache-it", tenant, Set.of(ConsoleRoles.ADMIN));
    var authority = new SimpleGrantedAuthority(ConsoleRoles.ADMIN);
    var authentication =
        new UsernamePasswordAuthenticationToken(principal, null, List.of(authority));
    SecurityContextHolder.getContext().setAuthentication(authentication);
    String calendarCode = "excel_cache_calendar";
    String cacheKey = "config:" + tenant + ":business-calendar:" + calendarCode;
    Map<String, String> calendar = Map.of(
        "tenant_id",
        tenant,
        "calendar_code",
        calendarCode,
        "calendar_name",
        "Excel cache calendar",
        "timezone",
        "UTC");
    PackageExcelSession session = PackageExcelSession.builder()
        .fileName("cache.xlsx")
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
        .permissionScope(Set.of())
        .build();
    String token = sessions.save(session);
    redis.opsForValue().set(cacheKey, "original");
    if (rollback) {
      doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("injected failure after registering cache callback");
          })
          .when(invalidation)
          .evictAllBusinessCalendars(eq(tenant));
    }
    String countCalendarSql =
        "select count(*) from batch.business_calendar where tenant_id = ? and calendar_code = ?";
    try {
      var preview = service.preview(token);
      assertThat(preview.invalidRows()).isZero();
      TenantConfigPackageExcelApplyRequest request = new TenantConfigPackageExcelApplyRequest();
      request.setPreviewToken(preview.previewToken());
      if (rollback) {
        assertThatThrownBy(() -> service.apply(token, request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("after registering cache callback");
      } else {
        var applied = service.apply(token, request);
        assertThat(applied.businessCalendarInserted()).isEqualTo(1);
      }
      Integer calendars =
          jdbc.queryForObject(countCalendarSql, Integer.class, tenant, calendarCode);
      String cached = redis.opsForValue().get(cacheKey);
      assertThat(calendars).isEqualTo(rollback ? 0 : 1);
      assertThat(cached).isEqualTo(rollback ? "original" : null);
      if (rollback) {
        assertThat(sessions.isApplyInProgress(token)).isFalse();
        assertThat(sessions.get(token)).isNotNull();
      }
    } finally {
      SecurityContextHolder.clearContext();
      redis.delete(cacheKey);
      sessions.remove(token);
      String deleteCalendarsSql = "delete from batch.business_calendar where tenant_id = ?";
      jdbc.update(deleteCalendarsSql, tenant);
    }
  }
}
