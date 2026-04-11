package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.example.batch.worker.dispatchs.config.DispatchChannelHealthProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时触发分发渠道健康探针，按配置的间隔周期性探测各渠道可用性。
 */
@Component
@RequiredArgsConstructor
public class DispatchChannelHealthScheduler {

    private final DispatchChannelHealthService healthService;
    private final DispatchChannelHealthProperties properties;

    @Scheduled(fixedDelayString = "${batch.worker.dispatch.health.probe-interval-millis:60000}")
    public void probe() {
        if (!properties.isEnabled()) {
            return;
        }
        healthService.probeConfiguredChannels();
    }
}
