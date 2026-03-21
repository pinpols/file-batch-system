package com.example.batch.worker.dispatchs.infrastructure.channel;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DispatchChannelGateway {

    private final List<DispatchChannelAdapter> adapters;

    public DispatchChannelGateway(List<DispatchChannelAdapter> adapters) {
        this.adapters = adapters;
    }

    public DispatchResult dispatch(DispatchCommand command) {
        String channelType = command.channelConfig() == null ? null : String.valueOf(command.channelConfig().get("channel_type"));
        return adapters.stream()
                .filter(adapter -> adapter.supports(channelType))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("unsupported channel type: " + channelType))
                .dispatch(command);
    }
}
