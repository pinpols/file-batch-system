package io.github.pinpols.batch.common.mq;

import java.util.concurrent.CompletableFuture;

/** 消息发布薄端口；应用层依赖本接口，具体 MQ 客户端留在 infrastructure 适配器。 */
public interface MqMessagePublisher {

  CompletableFuture<MqPublishResult> publish(MqMessage message);
}
