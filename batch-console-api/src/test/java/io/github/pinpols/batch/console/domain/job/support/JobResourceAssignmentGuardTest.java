package io.github.pinpols.batch.console.domain.job.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("作业物理资源分配授权")
class JobResourceAssignmentGuardTest {

  private final JobResourceAssignmentGuard guard = new JobResourceAssignmentGuard();

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("平台管理员可维护物理资源分配")
  void shouldAllowPlatformAdmin_whenAssigningPhysicalResources() {
    authenticate("ROLE_ADMIN");

    assertThatCode(() -> guard.assertCreateAllowed("queue_a", "IMPORT")).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("未认证访问不能维护或保留物理资源分配")
  void shouldRejectAnonymous_whenManagingPhysicalResources() {
    assertThatThrownBy(() -> guard.assertCreateAllowed(null, null))
        .isInstanceOf(BizException.class);
    assertThatThrownBy(guard::shouldPreserveExistingRouting).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("租户管理员创建作业时不能指定物理资源")
  void shouldRejectTenantAdmin_whenAssigningResourcesOnCreate() {
    authenticate("ROLE_TENANT_ADMIN");

    assertThatThrownBy(() -> guard.assertCreateAllowed("queue_a", null))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("租户管理员未提交物理资源字段时允许创建")
  void shouldAllowTenantAdmin_whenRouteOverridesAreBlank() {
    authenticate("ROLE_TENANT_ADMIN");

    assertThatCode(() -> guard.assertCreateAllowed(" ", null)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("租户管理员可保留原路由但不能修改路由")
  void shouldPreserveCurrentRouteAndRejectChanges_whenUpdatingAsTenantAdmin() {
    authenticate("ROLE_TENANT_ADMIN");

    assertThatCode(() -> guard.assertUpdateAllowed("QUEUE_A", "import", "queue_a", "IMPORT"))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> guard.assertUpdateAllowed("queue_b", null, "queue_a", "IMPORT"))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("租户管理员克隆作业时不能覆盖物理路由")
  void shouldRejectTenantAdmin_whenOverridingRouteOnCopy() {
    authenticate("ROLE_TENANT_ADMIN");

    assertThatThrownBy(() -> guard.assertCopyOverridesAllowed(null, "IMPORT"))
        .isInstanceOf(BizException.class);
  }

  private static void authenticate(String... authorities) {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("operator", "N/A", authorities));
  }
}
