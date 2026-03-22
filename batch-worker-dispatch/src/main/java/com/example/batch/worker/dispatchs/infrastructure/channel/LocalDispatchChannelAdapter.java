package com.example.batch.worker.dispatchs.infrastructure.channel;

import org.springframework.stereotype.Component;

/**
 * LOCAL channel: writes dispatch envelope under {@code target_endpoint} (or temp outbox).
 */
@Component
public class LocalDispatchChannelAdapter implements DispatchChannelAdapter {

    @Override
    public boolean supports(String channelType) {
        return channelType != null && "LOCAL".equalsIgnoreCase(channelType);
    }

    @Override
    public DispatchResult dispatch(DispatchCommand command) {
        return LocalOutboxDispatchSupport.writeFilesystemEnvelope(command, false, null);
    }
}
