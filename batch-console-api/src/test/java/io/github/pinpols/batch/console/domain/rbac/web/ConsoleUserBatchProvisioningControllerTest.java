package io.github.pinpols.batch.console.domain.rbac.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.shared.audit.AuditAction;
import io.github.pinpols.batch.console.support.web.Idempotent;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

@DisplayName("用户批量开通控制器: 幂等注解与审计注解契约")
class ConsoleUserBatchProvisioningControllerTest {

  @Test
  @DisplayName("仅提交接口要求幂等键, 预览与暂存不要求")
  void shouldRequireIdempotencyKeyOnlyForApply() throws NoSuchMethodException {
    Class<ConsoleUserBatchProvisioningController> controller =
        ConsoleUserBatchProvisioningController.class;

    assertThat(controller.isAnnotationPresent(Idempotent.class)).isFalse();
    assertThat(controller
            .getMethod("preview", MultipartFile.class)
            .isAnnotationPresent(Idempotent.class))
        .isFalse();
    assertThat(controller
            .getMethod(
                "patch", String.class, ConsoleUserBatchProvisioningController.PatchRequest.class)
            .isAnnotationPresent(Idempotent.class))
        .isFalse();
    assertThat(controller
            .getMethod(
                "apply", String.class, ConsoleUserBatchProvisioningController.ApplyRequest.class)
            .isAnnotationPresent(Idempotent.class))
        .isTrue();
  }

  @Test
  @DisplayName("提交接口审计以请求标识作为操作关联键")
  void shouldUseRequestIdAsOperationCorrelationKeyForApplyAudit() throws NoSuchMethodException {
    AuditAction audit = ConsoleUserBatchProvisioningController.class
        .getMethod("apply", String.class, ConsoleUserBatchProvisioningController.ApplyRequest.class)
        .getAnnotation(AuditAction.class);

    assertThat(audit.action()).isEqualTo("user.batchCreate");
    assertThat(audit.aggregateType()).isEqualTo("user_batch_operation");
    assertThat(audit.aggregateId()).isEqualTo("#request.requestId");
    assertThat(audit.recordParams()).isFalse();
  }

  @Test
  @DisplayName("操作查询接口接受可选的目标租户过滤参数")
  void shouldAcceptOptionalTargetTenantFilterForOperationLookup() throws NoSuchMethodException {
    Class<ConsoleUserBatchProvisioningController> controller =
        ConsoleUserBatchProvisioningController.class;

    assertThat(controller.getMethod("operation", UUID.class, String.class)).isNotNull();
    assertThat(controller.getMethod("findOperation", UUID.class, String.class)).isNotNull();
  }
}
