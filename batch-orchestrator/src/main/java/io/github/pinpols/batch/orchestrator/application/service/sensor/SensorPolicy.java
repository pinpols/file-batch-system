package io.github.pinpols.batch.orchestrator.application.service.sensor;

import io.github.pinpols.batch.common.enums.SensorType;

/**
 * ADR-028 Sensor SPI：每种 {@link SensorType} 一个 Spring bean，由 {@link SensorPolicyRegistry} 按 type 路由。
 *
 * <p>实现约束：
 *
 * <ul>
 *   <li>{@link #probe} 必须是无副作用的探测：不写业务表、不发 Kafka、不调用对端 mutating API
 *   <li>必须在 {@link SensorContext#timeRemaining()} 内尽快返回；强烈建议 ≤ 10s（HTTP_POLL 走独立连接池超时 10s）
 *   <li>外部抖动 / 临时错误返 {@link SensorProbeResult#error}，由 scheduler 退避重试，policy 内部不要无限重试
 * </ul>
 */
public interface SensorPolicy {

  SensorType type();

  SensorProbeResult probe(SensorContext ctx);
}
