package io.github.pinpols.batch.console.infrastructure.config;

import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_COMPLETION_DEADLINE_LOCAL_TIME;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_QUEUE_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_SOFT_RUNTIME_SECONDS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_WORKER_GROUP;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleRoles;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.PackageValidationResult;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.SheetResult;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("租户配置包应用权限:隔离平台资源字段")
class TenantConfigPackageExcelApplyAuthorizationTest {

  private final TenantConfigPackageExcelApplyAuthorization authorization =
      new TenantConfigPackageExcelApplyAuthorization();

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("租户管理员可应用业务配置和业务 SLA")
  void shouldAllowBusinessFields_whenTenantAdminAppliesPackage() {
    authenticate(ConsoleRoles.TENANT_ADMIN);
    PackageValidationResult result = result(
        List.of(),
        List.of(Map.of(COL_QUEUE_CODE, "", COL_WORKER_GROUP, "")),
        List.of(Map.of(COL_COMPLETION_DEADLINE_LOCAL_TIME, "04:00")));

    assertThatCode(() -> authorization.assertAllowed(result)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("租户管理员不能通过配置包创建或更新资源队列")
  void shouldRejectResourceQueueRows_whenTenantAdminAppliesPackage() {
    authenticate(ConsoleRoles.TENANT_ADMIN);

    assertThatThrownBy(() -> authorization.assertAllowed(
            result(List.of(Map.of("queue_code", "tenant-q")), List.of(), List.of())))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("platform_fields_forbidden");
  }

  @Test
  @DisplayName("租户管理员不能设置作业物理队列、Worker 分组或平台运行阈值")
  void shouldRejectPlatformFields_whenTenantAdminAppliesPackage() {
    authenticate(ConsoleRoles.TENANT_ADMIN);

    assertThatThrownBy(() -> authorization.assertAllowed(result(
            List.of(),
            List.of(Map.of(COL_QUEUE_CODE, "physical-q", COL_WORKER_GROUP, "IMPORT")),
            List.of())))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("platform_fields_forbidden");

    assertThatThrownBy(() -> authorization.assertAllowed(
            result(List.of(), List.of(), List.of(Map.of(COL_SOFT_RUNTIME_SECONDS, "900")))))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("platform_fields_forbidden");
  }

  @Test
  @DisplayName("租户管理员不能通过配置包设置 Pipeline 或 Workflow 节点的 Worker 分组")
  void shouldRejectWorkerRouting_whenTenantAdminAppliesPackage() {
    authenticate(ConsoleRoles.TENANT_ADMIN);

    assertThatThrownBy(() -> authorization.assertAllowed(
            resultWithRoutingRows(List.of(Map.of(COL_WORKER_GROUP, "IMPORT")), List.of())))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("platform_fields_forbidden");

    assertThatThrownBy(() -> authorization.assertAllowed(
            resultWithRoutingRows(List.of(), List.of(Map.of(COL_WORKER_GROUP, "IMPORT")))))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("platform_fields_forbidden");
  }

  @Test
  @DisplayName("平台管理员仍可应用包含平台资源的旧全量包")
  void shouldAllowLegacyPlatformFields_whenPlatformAdminAppliesPackage() {
    authenticate(ConsoleRoles.ADMIN);
    PackageValidationResult legacyPackage = result(
        List.of(Map.of("queue_code", "tenant-q")),
        List.of(Map.of(COL_QUEUE_CODE, "tenant-q", COL_WORKER_GROUP, "IMPORT")),
        List.of(Map.of(COL_SOFT_RUNTIME_SECONDS, "900")));

    assertThatCode(() -> authorization.assertAllowed(legacyPackage)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("缺少可信 Console 身份时拒绝应用")
  void shouldRejectApply_whenPrincipalIsMissing() {
    assertThatThrownBy(() -> authorization.assertAllowed(result(List.of(), List.of(), List.of())))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("apply_forbidden");
  }

  private static void authenticate(String role) {
    ConsolePrincipal principal = new ConsolePrincipal("operator", "tenant-a", Set.of(role));
    var authorities = Set.of(new SimpleGrantedAuthority(role));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
  }

  private static PackageValidationResult result(
      List<Map<String, String>> queues,
      List<Map<String, String>> jobs,
      List<Map<String, String>> monitoringPolicies) {
    return resultWithRoutingRows(queues, jobs, monitoringPolicies, List.of(), List.of());
  }

  private static PackageValidationResult resultWithRoutingRows(
      List<Map<String, String>> pipelines, List<Map<String, String>> workflowNodes) {
    return resultWithRoutingRows(List.of(), List.of(), List.of(), pipelines, workflowNodes);
  }

  private static PackageValidationResult resultWithRoutingRows(
      List<Map<String, String>> queues,
      List<Map<String, String>> jobs,
      List<Map<String, String>> monitoringPolicies,
      List<Map<String, String>> pipelineRows,
      List<Map<String, String>> workflowNodeRows) {
    SheetResult resourceQueues = sheet("resource_queue", queues);
    SheetResult businessCalendars = sheet("business_calendar", List.of());
    SheetResult batchWindows = sheet("batch_window", List.of());
    SheetResult jobRows = sheet("job_definition", jobs);
    SheetResult policies = sheet("job_monitoring_policy", monitoringPolicies);
    SheetResult channels = sheet("file_channel_config", List.of());
    SheetResult templates = sheet("file_template_config", List.of());
    SheetResult pipelines = sheet("pipeline_definition", pipelineRows);
    SheetResult steps = sheet("pipeline_step_definition", List.of());
    SheetResult workflows = sheet("workflow_definition", List.of());
    SheetResult nodes = sheet("workflow_node", workflowNodeRows);
    SheetResult edges = sheet("workflow_edge", List.of());
    return new PackageValidationResult(
        resourceQueues,
        businessCalendars,
        batchWindows,
        jobRows,
        policies,
        channels,
        templates,
        pipelines,
        steps,
        workflows,
        nodes,
        edges,
        List.of());
  }

  private static SheetResult sheet(String name, List<Map<String, String>> rows) {
    return new SheetResult(name, rows.size(), rows, List.of());
  }
}
