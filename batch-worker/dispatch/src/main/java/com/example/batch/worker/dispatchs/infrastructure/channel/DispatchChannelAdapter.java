package com.example.batch.worker.dispatchs.infrastructure.channel;

/** 分发渠道适配器接口，不同渠道（NAS/OSS/SFTP/EMAIL/HTTP 等）各自实现。 */
public interface DispatchChannelAdapter {

  /**
   * 判断本适配器是否支持指定渠道类型。
   *
   * @param channelType 渠道类型标识
   * @return 支持则返回 {@code true}
   */
  boolean supports(String channelType);

  /**
   * 执行分发操作，将文件投递到目标渠道。
   *
   * @param command 分发命令，包含文件记录、渠道配置及载荷
   * @return 分发结果
   */
  DispatchResult dispatch(DispatchCommand command);
}
