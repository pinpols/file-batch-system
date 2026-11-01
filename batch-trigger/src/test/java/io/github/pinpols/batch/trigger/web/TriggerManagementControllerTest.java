package io.github.pinpols.batch.trigger.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.trigger.domain.TriggerDrainService;
import io.github.pinpols.batch.trigger.domain.TriggerRegistrationService;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.PostMapping;

@ExtendWith(MockitoExtension.class)
@DisplayName("Trigger 管理接口支持以 WorkflowCode 管理调度")
class TriggerManagementControllerTest {

  @Mock
  private TriggerRegistrationService registrationService;

  @Mock
  private TriggerDrainService drainService;

  @Test
  @DisplayName("组合 Workflow 暂停结果返回 Workflow 身份并委托 Workflow 调度操作")
  void shouldPauseWorkflowByWorkflowCode() {
    var response = new TriggerManagementController(registrationService, drainService)
        .pauseWorkflow("tenant-a", "FLOW_A");

    assertThat(response.data().tenantId()).isEqualTo("tenant-a");
    assertThat(response.data().workflowCode()).isEqualTo("FLOW_A");
    assertThat(response.data().jobCode()).isNull();
    assertThat(response.data().status()).isEqualTo("PAUSED");
    verify(registrationService).pauseByWorkflowCode("tenant-a", "FLOW_A");
  }

  @Test
  @DisplayName("不再发布以 JobCode 为目标的旧调度管理路由")
  void shouldExposeOnlyWorkflowScopedScheduleActions() {
    List<String> postPaths = Arrays.stream(TriggerManagementController.class.getDeclaredMethods())
        .map(method -> method.getAnnotation(PostMapping.class))
        .filter(mapping -> mapping != null)
        .flatMap(mapping -> Arrays.stream(mapping.value()))
        .toList();

    assertThat(postPaths)
        .contains(
            "/workflow/register", "/workflow/unregister", "/workflow/pause", "/workflow/resume");
    assertThat(postPaths).doesNotContain("/register", "/unregister", "/pause", "/resume");
  }
}
