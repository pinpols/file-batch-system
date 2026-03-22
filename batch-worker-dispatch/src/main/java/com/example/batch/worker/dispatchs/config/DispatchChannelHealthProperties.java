package com.example.batch.worker.dispatchs.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.worker.dispatch.health")
public class DispatchChannelHealthProperties {

    private boolean enabled = true;
    private long probeIntervalMillis = 60_000L;
    private long maxBackoffMillis = 15 * 60_000L;
    private List<String> probeChannelTypes = new ArrayList<>(List.of("NAS", "OSS"));
}
