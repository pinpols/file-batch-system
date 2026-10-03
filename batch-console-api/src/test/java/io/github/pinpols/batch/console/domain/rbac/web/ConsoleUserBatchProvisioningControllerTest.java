package io.github.pinpols.batch.console.domain.rbac.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.shared.audit.AuditAction;
import io.github.pinpols.batch.console.support.web.Idempotent;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

class ConsoleUserBatchProvisioningControllerTest {

  @Test
  void onlyApplyRequiresIdempotencyKey() throws NoSuchMethodException {
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
  void applyAuditUsesRequestIdAsOperationCorrelationKey() throws NoSuchMethodException {
    AuditAction audit = ConsoleUserBatchProvisioningController.class
        .getMethod("apply", String.class, ConsoleUserBatchProvisioningController.ApplyRequest.class)
        .getAnnotation(AuditAction.class);

    assertThat(audit.action()).isEqualTo("user.batchCreate");
    assertThat(audit.aggregateType()).isEqualTo("user_batch_operation");
    assertThat(audit.aggregateId()).isEqualTo("#request.requestId");
    assertThat(audit.recordParams()).isFalse();
  }

  @Test
  void operationLookupAcceptsOptionalTargetTenantFilter() throws NoSuchMethodException {
    Class<ConsoleUserBatchProvisioningController> controller =
        ConsoleUserBatchProvisioningController.class;

    assertThat(controller.getMethod("operation", UUID.class, String.class)).isNotNull();
    assertThat(controller.getMethod("findOperation", UUID.class, String.class)).isNotNull();
  }
}
