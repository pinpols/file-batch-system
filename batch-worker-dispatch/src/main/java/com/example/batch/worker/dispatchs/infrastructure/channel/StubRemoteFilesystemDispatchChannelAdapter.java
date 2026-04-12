package com.example.batch.worker.dispatchs.infrastructure.channel;

import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * NAS / OSS 渠道存根适配器：以与 LOCAL 相同的方式将分发信封写入文件系统供运维捡收， 不实现真实远程传输协议——通过信封中的 {@code transportStub}
 * 字段显式标记该差异。
 */
@Component
public class StubRemoteFilesystemDispatchChannelAdapter implements DispatchChannelAdapter {

  private static final Set<String> SUPPORTED_TYPES = Set.of("NAS", "OSS");

  private static final String STUB_DETAIL =
      "Dedicated channel adapter not implemented; envelope written for operations (see"
          + " transportStub on JSON).";

  @Override
  public boolean supports(String channelType) {
    return channelType != null && SUPPORTED_TYPES.contains(channelType.toUpperCase());
  }

  @Override
  public DispatchResult dispatch(DispatchCommand command) {
    return LocalOutboxDispatchSupport.writeFilesystemEnvelope(command, true, STUB_DETAIL);
  }
}
