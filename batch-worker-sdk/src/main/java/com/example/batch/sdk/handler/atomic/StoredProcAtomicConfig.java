package com.example.batch.sdk.handler.atomic;

import java.util.Objects;
import java.util.Set;

/**
 * {@link StoredProcAtomicHandler} 配置 — 对齐平台 {@code StoredProcExecutor} 的"三道闸"安全模型(SDK 侧 JDK-only
 * 复刻)。
 *
 * <p>三道闸:
 *
 * <ol>
 *   <li><b>forbidOsCapableRole</b>(默认 true):PG 角色闸,拒绝 superuser / {@code pg_execute_server_program}
 *       / {@code pg_read_server_files} / {@code pg_write_server_files} 成员(存过 body
 *       不可审查,代码层唯一可靠防线是不给执行角色 OS 权限)。
 *   <li><b>allowedSchemas</b>(默认空 = dev 全放行):过程名的 schema(schema-qualified 取点号前)必须命中白名单。
 *   <li><b>allowSecurityDefiner</b>(默认 false):查 {@code pg_proc.prosecdef},SECURITY DEFINER 过程以
 *       owner 身份运行,可绕过角色闸提权碰 OS,故默认拒绝。
 * </ol>
 *
 * @param taskType handler 注册的 taskType
 * @param allowedSchemas schema 白名单;空 = 全放行(仅 dev,授权由最小权限 DB 角色保证)
 * @param allowSecurityDefiner 是否允许 SECURITY DEFINER 过程(默认 false)
 * @param forbidOsCapableRole 是否拒绝 OS 能力 DB 角色(默认 true)
 * @param statementTimeoutSeconds CallableStatement 查询超时(秒)
 */
public record StoredProcAtomicConfig(
    String taskType,
    Set<String> allowedSchemas,
    boolean allowSecurityDefiner,
    boolean forbidOsCapableRole,
    int statementTimeoutSeconds) {

  public StoredProcAtomicConfig {
    Objects.requireNonNull(taskType, "taskType");
    if (taskType.isBlank()) {
      throw new IllegalArgumentException("taskType must not be blank");
    }
    if (statementTimeoutSeconds <= 0) {
      throw new IllegalArgumentException("statementTimeoutSeconds must be positive");
    }
    allowedSchemas = allowedSchemas == null ? Set.of() : Set.copyOf(allowedSchemas);
  }

  /** 安全默认值:空白名单(dev 全放行)、拒 SECURITY DEFINER、拒 OS 能力角色、60s 超时。 */
  public static StoredProcAtomicConfig defaults(String taskType) {
    return new StoredProcAtomicConfig(taskType, Set.of(), false, true, 60);
  }
}
