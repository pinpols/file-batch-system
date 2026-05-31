package com.example.batch.sdk.handler.atomic;

/**
 * {@link SqlAtomicHandler} 的开箱即用配置。
 *
 * @param taskType 该 handler 注册的 taskType(由调用方决定,通常 "sql")
 * @param statementTimeoutSeconds JDBC {@code Statement.setQueryTimeout} 秒数(默认 30)
 * @param maxResultRows ResultSet 读取行数上限,超出截断并标记 truncated(默认 10000)
 * @param forbidOsCapableRole 是否在执行前跑 PG 角色闸,拒绝在具备 OS 能力的 DB 角色上跑 SQL(默认 true)
 */
public record SqlAtomicConfig(
    String taskType, int statementTimeoutSeconds, int maxResultRows, boolean forbidOsCapableRole) {

  /** 默认值:statementTimeoutSeconds=30、maxResultRows=10000、forbidOsCapableRole=true。 */
  public static SqlAtomicConfig defaults(String taskType) {
    return new SqlAtomicConfig(taskType, 30, 10000, true);
  }
}
