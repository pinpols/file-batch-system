package com.example.batch.worker.dispatchs.infrastructure.channel;

import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * NAS 渠道存根适配器:本地/IT 环境下没有真 NAS 服务,以与 LOCAL 相同的方式将分发信封写到文件系统 供运维捡收;通过信封中的 {@code transportStub}
 * 字段显式标记非真传输。
 *
 * <p>OSS 不在 supports 集合内:test profile 下的 IT 通过 {@code MinIOContainer} 起真实 OSS, 走 {@link
 * OssDispatchChannelAdapter} 验证端到端;若把 OSS 也存根, {@code
 * DispatchExternalChannelIntegrationTest#shouldDispatchFileToRealMinioObjectStorage} 等"对实
 * OSS"的断言无法工作。
 */
@Component
@Profile({"local", "test"})
@Order(1000)
public class StubRemoteFilesystemDispatchChannelAdapter implements DispatchChannelAdapter {

  private static final Set<String> SUPPORTED_TYPES = Set.of("NAS");

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
