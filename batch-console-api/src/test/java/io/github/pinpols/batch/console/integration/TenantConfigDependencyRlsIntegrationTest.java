package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.application.config.ConsoleConfigSyncApplicationService;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigCopyService;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncBundlePayload;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncImportRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.JobDefinitionSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.BusinessCalendarSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigCopyRequest.ConfigType;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigCopyRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.request.JobDependencyRuleRequest;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@SpringBootTest(classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(TenantConfigDependencyRlsIntegrationTest.RoleTransactions.class)
@DisplayName("租户配置依赖在普通数据库角色下导出及跨租户应用")
class TenantConfigDependencyRlsIntegrationTest extends AbstractIntegrationTest {

  private static final ThreadLocal<Boolean> APPLICATION_ROLE = new ThreadLocal<>();

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private ConsoleTenantConfigInitApplicationService initService;
  @Autowired private ConsoleTenantConfigCopyService copyService;
  @Autowired private ConsoleConfigSyncApplicationService syncService;

  private String sourceTenant;
  private List<String> targets;
  private String upstreamA;
  private String upstreamB;
  private String downstream;

  @BeforeEach
  void prepareTenantsAndApplicationRole() {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    sourceTenant = "dependency-source-" + suffix;
    targets = List.of("dependency-target-a-" + suffix, "dependency-target-b-" + suffix);
    upstreamA = "PARENT_A_" + suffix;
    upstreamB = "PARENT_B_" + suffix;
    downstream = "CHILD_" + suffix;
    for (String tenant : List.of(sourceTenant, targets.getFirst(), targets.getLast())) {
      jdbc.update("""
          insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')
          """, tenant, tenant);
      TestTenantCalendarFixtures.ensureDefaultCalendar(jdbc, tenant);
    }
    jdbc.execute("""
        DO $$ BEGIN
          IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'config_dependency_app_test') THEN
            CREATE ROLE config_dependency_app_test NOSUPERUSER NOBYPASSRLS;
          END IF;
        END $$
        """);
    jdbc.execute("GRANT USAGE ON SCHEMA batch TO config_dependency_app_test");
    jdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA batch "
        + "TO config_dependency_app_test");
    jdbc.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA batch "
        + "TO config_dependency_app_test");
  }

  @Test
  @DisplayName("导出在无继承数据库租户上下文的普通角色下保留全部依赖及逐边业务日对齐")
  void shouldExportAllDependencyRulesUnderNonBypassRole() {
    assertThat(initService.batchInit(requestFor(List.of(sourceTenant)), "test", "seed")
        .successTenants()).isOne();

    ConfigSyncBundlePayload bundle = asApplicationRole(
        () -> copyService.buildBundle(sourceTenant, Set.of(ConfigType.JOB_DEFINITION)));

    assertThat(bundle.getJobDefinitions()).hasSize(3);
    assertThat(bundle.getJobDefinitions()).filteredOn(job -> downstream.equals(job.getJobCode()))
        .singleElement().satisfies(job -> assertThat(job.getDependencyRules())
            .containsExactlyElementsOf(rules()));
  }

  @Test
  @DisplayName("Config Sync 在普通角色下为两个目标租户保存完整依赖且按六项记录日志")
  void shouldImportDependencyBundleIntoTwoTenantsUnderNonBypassRole() {
    ConfigSyncBundlePayload bundle = new ConfigSyncBundlePayload();
    bundle.setJobDefinitions(requestFor(targets).getJobDefinitions());
    ConfigSyncImportRequest request = new ConfigSyncImportRequest();
    request.setTenantId(sourceTenant);
    request.setSourceEnv(sourceTenant);
    request.setTargetEnv("integration-test");
    request.setTargetTenantIds(targets);
    request.setBundle(bundle);
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    try {
      var response = asApplicationRole(() -> syncService.importBundle(request));
      assertThat(response.result().successTenants()).isEqualTo(2);
      assertThat(response.result().failureTenants()).isZero();
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
    for (String target : targets) {
      assertThat(jdbc.queryForList("""
          select upstream_job_code || ':' || biz_date_alignment from batch.job_dependency
          where tenant_id = ? and downstream_job_code = ? order by dependency_order
          """, String.class, target, downstream))
          .containsExactly(upstreamA + ":PREVIOUS_BIZ_DAY", upstreamB + ":SAME_DAY");
    }
    assertThat(jdbc.queryForMap("""
        select total_items, success_items, failed_items, skipped_items, sync_status
        from batch.config_sync_log where tenant_id = ? and source_env = ?
        """, sourceTenant, sourceTenant))
        .containsEntry("total_items", 6).containsEntry("success_items", 6)
        .containsEntry("failed_items", 0).containsEntry("skipped_items", 0)
        .containsEntry("sync_status", "SUCCESS");
  }

  private TenantConfigBatchInitRequest requestFor(List<String> tenants) {
    JobDefinitionSpec child = job(downstream);
    child.setDependencyRules(rules());
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(tenants);
    request.setStrict(true);
    request.setJobDefinitions(List.of(child, job(upstreamB), job(upstreamA)));
    return request;
  }

  @Test
  @DisplayName("只读源事务中的严格复制仅回滚失败租户并保留已成功租户的依赖")
  void shouldIsolateTargetTransactionsFromReadOnlySourceAndOtherTenantFailure() {
    assertThat(initService.batchInit(requestFor(List.of(sourceTenant)), "test", "seed-source")
        .successTenants()).isOne();
    TenantConfigBatchInitRequest initialTargets = requestFor(targets);
    initialTargets.getJobDefinitions().getFirst().setDependencyRules(List.of());
    assertThat(initService.batchInit(initialTargets, "test", "seed-targets")
        .successTenants()).isEqualTo(2);
    jdbc.update("""
        insert into batch.job_dependency
          (tenant_id, downstream_job_code, upstream_job_code, dependency_order, biz_date_alignment)
        values (?, ?, ?, 0, 'SAME_DAY')
        """, targets.getLast(), upstreamA, downstream);
    TenantConfigCopyRequest request = new TenantConfigCopyRequest();
    request.setSourceTenantId(sourceTenant);
    request.setTargetTenantIds(targets);
    request.setConfigTypes(Set.of(ConfigType.JOB_DEFINITION));
    request.setJobCodes(List.of(downstream));
    request.setMode(TenantConfigBatchInitRequest.InitMode.UPSERT);

    var response = asApplicationRole(true, () -> copyService.copy(request, "test", "copy-targets"));

    assertThat(response.successTenants()).isOne();
    assertThat(response.failureTenants()).isOne();
    assertThat(jdbc.queryForList("""
        select upstream_job_code || ':' || biz_date_alignment from batch.job_dependency
        where tenant_id = ? and downstream_job_code = ? order by dependency_order
        """, String.class, targets.getFirst(), downstream))
        .containsExactly(upstreamA + ":PREVIOUS_BIZ_DAY", upstreamB + ":SAME_DAY");
    assertThat(jdbc.queryForList("""
        select downstream_job_code || ':' || upstream_job_code from batch.job_dependency
        where tenant_id = ?
        """, String.class, targets.getLast())).containsExactly(upstreamA + ":" + downstream);
  }

  private List<JobDependencyRuleRequest> rules() {
    return List.of(new JobDependencyRuleRequest(upstreamA, "PREVIOUS_BIZ_DAY"),
        new JobDependencyRuleRequest(upstreamB, "SAME_DAY"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("后置受管Workflow创建失败时回滚该租户全部写入并保留已成功租户")
  void shouldRollbackTenantWhenManagedWorkflowProvisioningFails(boolean strict) {
    String failedTenant = targets.getLast();
    jdbc.update("delete from batch.business_calendar where tenant_id = ?", failedTenant);
    TenantConfigBatchInitRequest request = requestFor(targets);
    request.setStrict(strict);

    var response = asApplicationRole(() -> initService.batchInit(request, "test", "fatal-target"));

    assertThat(response.successTenants()).isOne();
    assertThat(response.failureTenants()).isOne();
    assertThat(jdbc.queryForObject("select count(*) from batch.job_definition where tenant_id = ?",
        Integer.class, targets.getFirst())).isEqualTo(3);
    assertThat(jdbc.queryForObject("select count(*) from batch.workflow_definition where tenant_id = ?",
        Integer.class, targets.getFirst())).isEqualTo(3);
    assertThat(jdbc.queryForObject("select count(*) from batch.job_definition where tenant_id = ?",
        Integer.class, failedTenant)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from batch.job_dependency where tenant_id = ?",
        Integer.class, failedTenant)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from batch.workflow_definition where tenant_id = ?",
        Integer.class, failedTenant)).isZero();
  }

  @Test
  @DisplayName("空租户同包创建日历及多依赖Job时先落实日历再生成受管Workflow")
  void shouldApplyBundledCalendarBeforeJobsAndManagedWorkflows() {
    String target = targets.getLast();
    jdbc.update("delete from batch.business_calendar where tenant_id = ?", target);
    TenantConfigBatchInitRequest request = requestFor(List.of(target));
    BusinessCalendarSpec calendar = new BusinessCalendarSpec();
    calendar.setCalendarCode("default_calendar");
    calendar.setCalendarName("Bundled calendar");
    calendar.setTimezone("Asia/Shanghai");
    calendar.setEnabled(true);
    request.setBusinessCalendars(List.of(calendar));

    var response = asApplicationRole(() -> initService.batchInit(request, "test", "cold-target"));

    assertThat(response.successTenants()).isOne();
    assertThat(response.failureTenants()).isZero();
    assertThat(response.results()).singleElement()
        .satisfies(result -> assertThat(result.businessCalendars().created()).isOne());
    assertThat(jdbc.queryForObject("select count(*) from batch.job_definition where tenant_id = ?",
        Integer.class, target)).isEqualTo(3);
    assertThat(jdbc.queryForObject("""
        select count(*) from batch.workflow_definition workflow
        join batch.business_calendar calendar on calendar.tenant_id = workflow.tenant_id
          and calendar.calendar_code = workflow.calendar_code
        where workflow.tenant_id = ? and workflow.single_job_definition_id is not null
        """, Integer.class, target)).isEqualTo(3);
    assertThat(jdbc.queryForList("""
        select upstream_job_code || ':' || biz_date_alignment from batch.job_dependency
        where tenant_id = ? and downstream_job_code = ? order by dependency_order
        """, String.class, target, downstream))
        .containsExactly(upstreamA + ":PREVIOUS_BIZ_DAY", upstreamB + ":SAME_DAY");
  }

  private static JobDefinitionSpec job(String code) {
    JobDefinitionSpec spec = new JobDefinitionSpec();
    spec.setJobCode(code);
    spec.setJobName(code);
    spec.setJobType("IMPORT");
    spec.setScheduleType("MANUAL");
    spec.setStartGraceSeconds(0);
    spec.setEnabled(true);
    return spec;
  }

  private <T> T asApplicationRole(Supplier<T> operation) {
    return asApplicationRole(false, operation);
  }

  private <T> T asApplicationRole(boolean readOnly, Supplier<T> operation) {
    APPLICATION_ROLE.set(true);
    try {
      TransactionTemplate transaction = new TransactionTemplate(transactions);
      transaction.setReadOnly(readOnly);
      return transaction.execute(status -> {
        assertThat(jdbc.queryForObject("select current_user", String.class))
            .isEqualTo("config_dependency_app_test");
        return RlsTenantContextHolder.runWithTenant(sourceTenant, () -> {
          T result = operation.get();
          assertThat(RlsTenantContextHolder.get()).isEqualTo(sourceTenant);
          assertThat(jdbc.queryForObject("select current_setting('app.tenant_id', true)",
              String.class)).isNotIn(targets);
          return result;
        });
      });
    } finally {
      APPLICATION_ROLE.remove();
    }
  }

  /** Apply the role to every new transaction, including suspended-parent REQUIRES_NEW work. */
  @TestConfiguration(proxyBeanMethods = false)
  static class RoleTransactions {
    @Bean
    @Primary
    PlatformTransactionManager configDependencyTransactions(DataSource dataSource) {
      return new JdbcTransactionManager(dataSource) {
        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
          super.doBegin(transaction, definition);
          if (Boolean.TRUE.equals(APPLICATION_ROLE.get())) {
            var connection = DataSourceUtils.getConnection(dataSource);
            try (var statement = connection.createStatement()) {
              statement.execute("SET LOCAL ROLE config_dependency_app_test");
              statement.execute("SET LOCAL app.tenant_id = ''");
            } catch (SQLException failure) {
              throw new CannotCreateTransactionException("Could not apply test role", failure);
            } finally {
              DataSourceUtils.releaseConnection(connection, dataSource);
            }
          }
        }
      };
    }
  }
}
