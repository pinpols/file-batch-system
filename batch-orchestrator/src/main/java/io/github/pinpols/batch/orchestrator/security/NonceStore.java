package io.github.pinpols.batch.orchestrator.security;

import java.time.Duration;

/** 一次性 nonce 去重存储（防重放）。原子登记：首次返回 true，已存在返回 false。 */
public interface NonceStore {

  /**
   * 原子登记 (tenant, nonce)；首次写入返回 true，已存在返回 false（视为重放）。
   *
   * @param ttl 过期时间，应 ≥ 时钟偏移窗口，过期后 nonce 可复用（彼时原请求已因 ts 超窗被拒）。
   */
  boolean registerIfAbsent(String tenantId, String nonce, Duration ttl);
}
