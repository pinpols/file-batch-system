package com.example.batch.worker.atomic.runtime;

import java.util.Map;

/**
 * Atomic worker 四类 executor 的"在跑 effective 配置"快照 — 提供给 Console / 运维仪表盘可视化。
 *
 * <p>Round-3 #8(Round-2 §4 P0 #8):#252-K1 在 prod profile 下隐式把 {@code http.enforce-allowlist} 翻
 * true, 缺启动期可见信号 + 缺仪表盘对账渠道。本快照把 4 个 executor 的安全门控字段(enabled / 白名单大小 / 方言等) 显式暴露,Console 反向 HTTP
 * 拉取后展示在 /ops/atomic-runtime 菜单。
 *
 * <p>纯只读快照,不含密钥 / 连接串 / 主机名等敏感信息。
 */
public record AtomicRuntimeStatus(
    String workerCode,
    String workerType,
    ShellStatus shell,
    SqlStatus sql,
    HttpStatus http,
    StoredProcStatus storedProc) {

  /** Shell executor 状态:开关 + 程序白名单大小。 */
  public record ShellStatus(boolean enabled, int commandWhitelistSize) {}

  /** SQL executor 状态:开关 + 当前连接方言(DB product name,JDBC 探测)。 */
  public record SqlStatus(boolean enabled, String dialect) {}

  /**
   * HTTP executor 状态:开关 + enforceAllowlist 当前 effective 值 + 出口白名单 host pattern 数量。
   *
   * <p>{@code enforceAllowlistSource} = explicit / prod-default / dev-default,标识当前 effective 值的来源,
   * Console UI 可据此提示运维"prod-default = 隐式翻 true,可显式配以覆盖"。
   */
  public record HttpStatus(
      boolean enabled,
      boolean enforceAllowlist,
      String enforceAllowlistSource,
      int allowlistHostsSize) {}

  /** Stored Proc executor 状态:开关 + 允许 schema 数量。 */
  public record StoredProcStatus(boolean enabled, int allowedSchemasSize) {}

  /** 转 LinkedHashMap 方便 Console 上的 ParameterizedTypeReference 解析 / JSON 序列化稳定字段顺序。 */
  public Map<String, Object> asMap() {
    return Map.of(
        "workerCode", workerCode == null ? "" : workerCode,
        "workerType", workerType == null ? "" : workerType,
        "shell", shell,
        "sql", sql,
        "http", http,
        "storedProc", storedProc);
  }
}
