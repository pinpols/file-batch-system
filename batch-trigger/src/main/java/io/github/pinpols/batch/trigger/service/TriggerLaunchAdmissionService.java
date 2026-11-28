package io.github.pinpols.batch.trigger.service;

import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import io.github.pinpols.batch.trigger.config.TriggerApiAdmissionGuard;
import io.github.pinpols.batch.trigger.domain.TriggerDrainService;

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
