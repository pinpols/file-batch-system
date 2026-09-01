package io.github.pinpols.batch.trigger.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.trigger.domain.TriggerRegistrationService;
import io.github.pinpols.batch.trigger.domain.TriggerStatusInfo;
import io.github.pinpols.batch.trigger.infrastructure.TriggerGracefulShutdown;
import io.github.pinpols.batch.trigger.infrastructure.TriggerGracefulShutdown.TriggerDrainStatus;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.quartz.SchedulerException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Trigger 只读状态查询接口。写操作保留在 {@link TriggerManagementController}。 */
@RestController
@RequestMapping("/api/triggers/management")
@RequiredArgsConstructor
public class TriggerStatusController {

  private static final String KEY_STATUS = "status";

  private final TriggerRegistrationService triggerRegistrationService;
  private final TriggerGracefulShutdown gracefulShutdown;

  @GetMapping("/list")
  public CommonResponse<List<TriggerStatusInfo>> list() {
    return CommonResponse.success(triggerRegistrationService.listRegisteredTriggers());
  }

  @GetMapping("/scheduler-status")
  public CommonResponse<Map<String, String>> schedulerStatus() {
    return CommonResponse.success(Map.of(KEY_STATUS, triggerRegistrationService.schedulerStatus()));
  }

  @GetMapping("/drain/status")
  public CommonResponse<TriggerDrainStatus> drainStatus() throws SchedulerException {
    return CommonResponse.success(gracefulShutdown.status());
  }
}
