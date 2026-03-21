package com.example.batch.worker.dispatchs.infrastructure.channel;

public interface DispatchChannelAdapter {

    boolean supports(String channelType);

    DispatchResult dispatch(DispatchCommand command);
}
