/**
 * SDK 内部 —— 任务派发运行时:Kafka 消费({@link KafkaTaskConsumer})、claim → execute → report 编排({@link
 * TaskDispatcher})、心跳指令与 worker 运行态机。由 {@code client} 装配并管理生命周期。
 *
 * <p><b>非租户 API</b>:租户只需通过 {@code io.github.pinpols.batch.sdk.client.BatchPlatformClient} 注册
 * handler,不应直接操作本包。
 */
package io.github.pinpols.batch.sdk.dispatcher;
