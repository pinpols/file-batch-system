package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import java.util.OptionalLong;

/**
 * ADR-041 Phase1.5:可回读能力。渠道适配器可选实现 —— 投递成功后从目的端 stat 已落地对象的字节数,供 {@code DeliverDispatchStep}
 * 与登记的期望大小对账,抓传输损坏 / 半写。实现方<b>只读 stat,不改目的端</b>; 读不到 / 不存在返回 {@link
 * OptionalLong#empty()}。未实现本接口的渠道视为「不支持回读」,opt-in 校验静默跳过。
 */
public interface DispatchReadbackCapable {

  /** 读回已投递目标对象的字节数;不可得 / 不存在返回 empty。 */
  OptionalLong readbackSize(DispatchCommand command);
}
