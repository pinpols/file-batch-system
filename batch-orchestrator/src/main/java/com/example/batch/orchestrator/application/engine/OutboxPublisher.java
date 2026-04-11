package com.example.batch.orchestrator.application.engine;

import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;

import java.util.concurrent.CompletableFuture;

public interface OutboxPublisher {

    /**
     * 异步发送 Outbox 事件到 Kafka。
     *
     * <p>Future 正常完成（true）表示 Broker 已确认；完成（false）或异常表示发送失败。 调用方应在所有 future 完成后统一更新 DB 状态，而非在 future
     * 内部写库。
     */
    CompletableFuture<Boolean> publish(OutboxEventEntity event);
}
