package com.example.batch.sdk.checkpoint;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ADR-037 决策一 — 进程内断点实现,供<b>单测 / 示例</b>。
 *
 * <p>用 {@link ConcurrentHashMap} 按 {@code taskId} 存最新状态。<b>无持久化:进程重启即丢,不要用于生产</b>;生产请用同事务的 {@code
 * JdbcSdkCheckpoint} 或租户自家控制表实现。
 *
 * <p>注意:这里的 {@link #save} 是单纯写 Map,<b>不提供业务数据同事务</b>(进程内 Map 没有事务概念)。它只用来跑通续跑 / 取消的协议语义, 真要的「业务 +
 * 断点同事务」原子性由 JDBC 实现兜底。
 */
public final class InMemorySdkCheckpoint implements SdkCheckpoint {

  private final Map<String, SdkCheckpointState> store = new ConcurrentHashMap<>();

  @Override
  public Optional<SdkCheckpointState> load(String taskId) {
    return Optional.ofNullable(store.get(taskId));
  }

  @Override
  public void save(String taskId, SdkCheckpointState state) {
    store.put(taskId, state);
  }

  /** 测试辅助:当前已保存的断点条数。 */
  public int size() {
    return store.size();
  }
}
