package io.github.pinpols.batch.trigger.service;

import io.github.pinpols.batch.trigger.config.TriggerApiAdmissionGuard;
import io.github.pinpols.batch.trigger.domain.TriggerDrainService;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 将并发准入与排水复核集中在 Trigger 写用例入口。 */
@Service
@RequiredArgsConstructor
public class TriggerLaunchAdmissionService {

  private final TriggerApiAdmissionGuard admissionGuard;
  private final TriggerDrainService drainService;

  public <T> T execute(Supplier<T> launch) {
    return admissionGuard.execute(() -> {
      drainService.ensureAcceptingLaunches();
      return launch.get();
    });
  }
}
