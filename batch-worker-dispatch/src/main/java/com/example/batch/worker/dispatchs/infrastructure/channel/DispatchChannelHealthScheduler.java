package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.example.batch.worker.dispatchs.config.DispatchChannelHealthProperties;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
