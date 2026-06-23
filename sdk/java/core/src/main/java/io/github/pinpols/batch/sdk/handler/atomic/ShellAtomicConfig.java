package io.github.pinpols.batch.sdk.handler.atomic;

import java.util.Set;

/**
 * {@link ShellAtomicHandler} 的开箱即用配置。
 *
 * @param taskType 注册到平台的 task type 标识(默认 "shell")
 * @param allowedCommands 命令白名单(绝对路径精确匹配)。**非空时**强制校验,command 必须命中否则拒绝。<br>
 *     **空集合 = dev 全放行**。⚠️ 生产环境必须显式配置白名单,否则等于把任意可执行文件暴露给派单方(RCE)。
 * @param timeoutSeconds 子进程超时(秒),超时 {@code destroyForcibly} 并转 fail(默认 60)
 * @param maxOutputBytes stdout / stderr 各自的字节上限,超出截断(默认 64 KiB)
 * @param cleanupWorkdir 执行后是否递归删除临时 workdir(默认 true)
 */
public record ShellAtomicConfig(
    String taskType,
    Set<String> allowedCommands,
    int timeoutSeconds,
    int maxOutputBytes,
    boolean cleanupWorkdir) {

  public ShellAtomicConfig {
    if (taskType == null || taskType.isBlank()) {
      throw new IllegalArgumentException("taskType must not be blank");
    }
    allowedCommands = allowedCommands == null ? Set.of() : Set.copyOf(allowedCommands);
    if (timeoutSeconds <= 0) {
      throw new IllegalArgumentException("timeoutSeconds must be > 0");
    }
    if (maxOutputBytes <= 0) {
      throw new IllegalArgumentException("maxOutputBytes must be > 0");
    }
  }

  /** 默认配置:空白名单(dev 全放行)、60s 超时、64 KiB 截断、执行后清理 workdir。 */
  public static ShellAtomicConfig defaults(String taskType) {
    return new ShellAtomicConfig(taskType, Set.of(), 60, 64 * 1024, true);
  }
}
