package com.example.batch.worker.dispatchs.infrastructure.channel;

import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * NAS / OSS / SFTP / EMAIL: persists the same filesystem envelope as LOCAL for pickup.
 * Does not implement real remote protocols — aligns with design by making the gap explicit in {@code transportStub} on the envelope.
 */
@Component
public class StubRemoteFilesystemDispatchChannelAdapter implements DispatchChannelAdapter {

    private static final Set<String> SUPPORTED_TYPES = Set.of("NAS", "OSS");

    private static final String STUB_DETAIL =
            "Dedicated channel adapter not implemented; envelope written for operations (see transportStub on JSON).";

    @Override
    public boolean supports(String channelType) {
        return channelType != null && SUPPORTED_TYPES.contains(channelType.toUpperCase());
    }

    @Override
    public DispatchResult dispatch(DispatchCommand command) {
        return LocalOutboxDispatchSupport.writeFilesystemEnvelope(command, true, STUB_DETAIL);
    }
}
