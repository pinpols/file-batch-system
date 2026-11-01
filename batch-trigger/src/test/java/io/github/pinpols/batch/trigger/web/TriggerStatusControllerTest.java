package io.github.pinpols.batch.trigger.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.trigger.domain.TriggerSchedulerStatus;
import io.github.pinpols.batch.trigger.domain.TriggerStatusInfo;
import io.github.pinpols.batch.trigger.domain.TriggerStatusQueryService;
import io.github.pinpols.batch.trigger.domain.TriggerDrainService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Trigger 状态查询 REST 入口:按租户查询已注册作业")
class TriggerStatusControllerTest {

  @Mock
  private TriggerStatusQueryService triggerStatusQueryService;

  @Mock
  private TriggerDrainService drainService;

  @Test
  @DisplayName("租户参数传递给状态查询服务并返回查询结果")
  void shouldPassTenantIdToQueryService() {
    TriggerStatusInfo trigger = TriggerStatusInfo.builder()
        .tenantId("tenant-a")
        .jobCode("JOB_A")
        .status("NORMAL")
        .build();
    when(triggerStatusQueryService.listRegisteredTriggers("tenant-a")).thenReturn(List.of(trigger));

    CommonResponse<List<TriggerStatusInfo>> response =
        new TriggerStatusController(triggerStatusQueryService, drainService).list("tenant-a");

    assertThat(response.data()).containsExactly(trigger);
    verify(triggerStatusQueryService).listRegisteredTriggers("tenant-a");
  }

  @Test
  @DisplayName("调度器状态查询映射为状态响应")
  void shouldWrapSchedulerStatus() {
    when(triggerStatusQueryService.schedulerStatus()).thenReturn("RUNNING");

    CommonResponse<TriggerSchedulerStatus> response =
        new TriggerStatusController(triggerStatusQueryService, drainService).schedulerStatus();

    assertThat(response.data()).isEqualTo(new TriggerSchedulerStatus("RUNNING"));
    verify(triggerStatusQueryService).schedulerStatus();
  }
}
