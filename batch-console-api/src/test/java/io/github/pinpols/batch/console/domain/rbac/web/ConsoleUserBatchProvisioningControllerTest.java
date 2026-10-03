package io.github.pinpols.batch.console.domain.rbac.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.support.web.Idempotent;
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
}
