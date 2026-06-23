package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchFileContentResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * NAS 渠道分发适配器，将文件拷贝到 NAS 远程目录。
 *
 * <p>{@code @Profile("!local & !test")}：local / test profile 下让位给 {@link
 * StubRemoteFilesystemDispatchChannelAdapter}，避免本机真实写 NAS。
 */
@Component
@Profile("!local & !test")
@Order(10)
@RequiredArgsConstructor
public class NasDispatchChannelAdapter implements DispatchChannelAdapter {

  private final DispatchFileContentResolver contentResolver;

  @Override
  public boolean supports(String channelType) {
    return channelType != null && "NAS".equalsIgnoreCase(channelType);
  }

  @Override
  public DispatchResult dispatch(DispatchCommand command) {
    return RemoteFilesystemDispatchSupport.dispatchNas(command, contentResolver);
  }
}
