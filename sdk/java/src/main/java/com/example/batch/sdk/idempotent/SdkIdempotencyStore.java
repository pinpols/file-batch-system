package com.example.batch.sdk.idempotent;

import java.util.Optional;

/**
 * A.3 — 幂等去重存储 SPI。由 SDK 接入方(租户)注入实现,存进自家业务库 / Redis。
 *
 * <p><b>红线</b>:实现**只能**写租户自己的存储,**禁**碰平台 {@code job_instance} / {@code outbox_event} 等状态表。SDK core
 * 不提供 JDBC / Spring 默认实现,仅给 {@link NoOp}(永不去重,等价关闭)。
 *
 * <p>典型语义:{@link #tryAcquire} 原子抢执行权;抢不到时 {@link #find} 命中已记录结果 → handler 跳过执行直接返该结果;抢到 → 执行后调
 * {@link #record} 回填成功结果。实现需保证 {@code tryAcquire} 用底层原子原语(如 {@code INSERT ... ON CONFLICT DO
 * NOTHING} / Redis {@code SET NX})互斥,严禁 SELECT-then-INSERT;同时 {@code record}
 * 的写入与租户业务写在同一事务(否则去重不可靠), 这由租户实现负责,SDK 不介入其事务边界。
 */
public interface SdkIdempotencyStore {

  /**
   * 原子地为 key 抢执行权。
   *
   * @param key 解析后的幂等键
   * @param ttlMillis 存活毫秒({@code <= 0} = 永久),由实现决定如何使用
   * @return true=抢到执行权,应执行业务;false=已有执行中占位或成功记录,调用方随后用 {@link #find} 判断能否短路
   */
  boolean tryAcquire(String key, long ttlMillis);

  /**
   * 查 key 是否已有成功记录。
   *
   * @param key 解析后的幂等键
   * @return 已记录结果(命中)/ 空(未命中,需执行)
   */
  Optional<SdkIdempotencyEntity> find(String key);

  /**
   * 记录一次成功执行结果。
   *
   * @param key 解析后的幂等键
   * @param record 结果快照(message + output)
   * @param ttlMillis 存活毫秒({@code <= 0} = 永久),由实现决定如何使用
   */
  void record(String key, SdkIdempotencyEntity record, long ttlMillis);

  /**
   * 释放未回填成功结果的执行中占位。业务失败或抛异常时调用,让平台重派后可重新抢占。已回填成功结果的 key 应保持不动。
   *
   * @param key 解析后的幂等键
   */
  void release(String key);

  /** 默认 no-op 实现 —— 永不命中,等价关闭幂等(便于本地 / 测试占位)。 */
  final class NoOp implements SdkIdempotencyStore {
    @Override
    public boolean tryAcquire(String key, long ttlMillis) {
      return true;
    }

    @Override
    public Optional<SdkIdempotencyEntity> find(String key) {
      return Optional.empty();
    }

    @Override
    public void record(String key, SdkIdempotencyEntity record, long ttlMillis) {
      // 无操作
    }

    @Override
    public void release(String key) {
      // 无操作
    }
  }
}
