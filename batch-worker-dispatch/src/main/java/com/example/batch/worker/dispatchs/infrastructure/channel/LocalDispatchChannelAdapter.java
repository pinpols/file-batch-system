package com.example.batch.worker.dispatchs.infrastructure.channel;

import org.springframework.stereotype.Component;

/** LOCAL 渠道分发适配器：将分发信封写入 {@code target_endpoint} 指定目录（或系统临时 outbox 目录）。 */
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
