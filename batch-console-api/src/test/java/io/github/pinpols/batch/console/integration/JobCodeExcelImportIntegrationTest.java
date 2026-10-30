package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import io.github.pinpols.batch.console.application.config.TenantConfigPackageExcelService;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.JobDefinitionSpec;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("配置包作业编码的真实预览与应用")
class JobCodeExcelImportIntegrationTest extends AbstractIntegrationTest {

  private final TenantConfigPackageExcelService excel;

  private final TenantConfigPackageExcelImportStore sessions;

  private final JdbcTemplate jdbc;

  private final ConsoleTenantConfigInitApplicationService init;

  @Autowired
  JobCodeExcelImportIntegrationTest(
      TenantConfigPackageExcelService excel,
      TenantConfigPackageExcelImportStore sessions,
      JdbcTemplate jdbc,
      ConsoleTenantConfigInitApplicationService init) {
    this.excel = excel;
    this.sessions = sessions;
    this.jdbc = jdbc;
    this.init = init;
  }

  @AfterEach
  void clearPrincipal() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("非法作业编码预览拒绝且不落库，合法编码可应用")
  void shouldRejectInvalidCodeAndApplyValidCode() {
    String tenant = "job-code-it-" + Long.toUnsignedString(System.nanoTime());
    ConsolePrincipal principal =
        new ConsolePrincipal("job-code-it", tenant, Set.of(ConsoleRoles.ADMIN));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority(ConsoleRoles.ADMIN))));
    String insertCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, enabled)
        values (?, 'default_calendar', 'Default calendar', 'UTC', true)
        """;
    jdbc.update(insertCalendarSql, tenant);
    String invalidToken = sessions.save(session(tenant, "A:B"));
    String validToken = null;
    try {
      var invalidPreview = excel.preview(invalidToken);
      assertThat(invalidPreview.invalidRows()).isEqualTo(1);
      TenantConfigPackageExcelApplyRequest invalidRequest =
          new TenantConfigPackageExcelApplyRequest();
      invalidRequest.setPreviewToken(invalidPreview.previewToken());
      assertThatThrownBy(() -> excel.apply(invalidToken, invalidRequest))
          .isInstanceOf(BizException.class);
      assertThat(countJob(tenant, "A:B")).isZero();

      validToken = sessions.save(session(tenant, "A_B"));
      var validPreview = excel.preview(validToken);
      assertThat(validPreview.invalidRows()).isZero();
      TenantConfigPackageExcelApplyRequest validRequest =
          new TenantConfigPackageExcelApplyRequest();
      validRequest.setPreviewToken(validPreview.previewToken());
      assertThat(excel.apply(validToken, validRequest).jobInserted()).isEqualTo(1);
      assertThat(countJob(tenant, "A_B")).isEqualTo(1);
    } finally {
      sessions.remove(invalidToken);
      if (validToken != null) {
        sessions.remove(validToken);
      }
    }
  }

  @Test
  @DisplayName("JSON 批量初始化拒绝非法作业编码且保留合法作业")
  void shouldRejectInvalidJobCodeDuringBatchInit() {
    String tenant = "job-code-init-" + Long.toUnsignedString(System.nanoTime());
    String insertCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, enabled)
        values (?, 'default_calendar', 'Default calendar', 'UTC', true)
        """;
    jdbc.update(insertCalendarSql, tenant);
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(List.of(tenant));
    request.setJobDefinitions(List.of(job("A:B"), job("A_B")));
    request.setStrict(false);

    var result = init.batchInit(request, "job-code-it", "job-code-" + tenant);

    assertThat(result.failureTenants()).isZero();
    assertThat(result.results().getFirst().jobDefinitions().failed()).isEqualTo(1);
    assertThat(result.results().getFirst().jobDefinitions().created()).isEqualTo(1);
    assertThat(countJob(tenant, "A:B")).isZero();
    assertThat(countJob(tenant, "A_B")).isEqualTo(1);
  }

  @Test
  @DisplayName("严格模式遇到非法作业编码时回滚合法兄弟作业")
  void shouldRollbackValidJobWhenStrictBatchContainsInvalidCode() {
    String tenant = "job-code-strict-" + Long.toUnsignedString(System.nanoTime());
    String insertCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, enabled)
        values (?, 'default_calendar', 'Default calendar', 'UTC', true)
        """;
    jdbc.update(insertCalendarSql, tenant);
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(List.of(tenant));
    request.setJobDefinitions(List.of(job("A_B"), job("A:B")));
    request.setStrict(true);

    var result = init.batchInit(request, "job-code-it", "job-code-" + tenant);

    assertThat(result.failureTenants()).isEqualTo(1);
    assertThat(countJob(tenant, "A:B")).isZero();
    assertThat(countJob(tenant, "A_B")).isZero();
  }

  @Test
  @DisplayName("初始化演练识别非法作业编码且不创建任何作业")
  void shouldValidateJobCodeDuringBatchInitDryRun() {
    String tenant = "job-code-dryrun-" + Long.toUnsignedString(System.nanoTime());
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(List.of(tenant));
    request.setJobDefinitions(List.of(job("A:B"), job("A_B")));
    request.setDryRun(true);

    var result = init.batchInit(request, "job-code-it", "job-code-" + tenant);

    assertThat(result.failureTenants()).isZero();
    assertThat(result.results().getFirst().jobDefinitions().failed()).isEqualTo(1);
    assertThat(result.results().getFirst().jobDefinitions().created()).isEqualTo(1);
    assertThat(countJob(tenant, "A:B")).isZero();
    assertThat(countJob(tenant, "A_B")).isZero();
  }

  private JobDefinitionSpec job(String code) {
    JobDefinitionSpec job = new JobDefinitionSpec();
    job.setJobCode(code);
    job.setJobName("test job");
    job.setJobType("IMPORT");
    job.setScheduleType("MANUAL");
    return job;
  }

  private PackageExcelSession session(String tenant, String code) {
    Map<String, String> row = Map.of(
        "tenant_id",
        tenant,
        "job_code",
        code,
        "job_name",
        "test job",
        "job_type",
        "IMPORT",
        "schedule_type",
        "MANUAL");
    return PackageExcelSession.builder()
        .fileName("job-code.xlsx")
        .tenantId(tenant)
        .uploadedAt(Instant.now())
        .resourceQueueRows(List.of())
        .businessCalendarRows(List.of())
        .batchWindowRows(List.of())
        .jobRows(List.of(row))
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

  private int countJob(String tenant, String code) {
    String sql = "select count(*) from batch.job_definition where tenant_id = ? and job_code = ?";
    return jdbc.queryForObject(sql, Integer.class, tenant, code);
  }
}
