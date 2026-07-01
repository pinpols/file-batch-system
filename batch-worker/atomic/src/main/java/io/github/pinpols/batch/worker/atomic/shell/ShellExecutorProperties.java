package io.github.pinpols.batch.worker.atomic.shell;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@link ShellTaskExecutor} 的配置 — 默认全关,业务方按需开 + 配白名单 + 配隔离目录。
 *
 * <p>设计依据:{@code docs/design/task-spi-design.md} §决策"ShellTaskExecutor 默认是否启用"=默认关。
 *
 * <p>安全防护链(全在本类配置):
 *
 * <ol>
 *   <li>{@link #enabled}:总开关,默认 false → executor 不注册,SPI registry 找不到 "shell" type
 *   <li>{@link #commandWhitelist}:程序路径白名单,空集合 = 允许全部(仅推荐内部信任环境);非空 = 只允许集合内
 *   <li>{@link #workdirBase}:工作目录基础路径,每次执行在它下面建唯一子目录(执行后清理)
 *   <li>{@link #defaultTimeout}:任务超时,业务方在 parameters 里可缩短但不能延长
 *   <li>{@link #maxStdoutBytes} / {@link #maxStderrBytes}:输出截断,防 OOM
 *   <li>{@link #allowedEnvKeys}:环境变量白名单,只透传名单内的 env(防泄密 / 防覆盖 PATH/LD_PRELOAD)
 * </ol>
 */
@Data
@ConfigurationProperties(prefix = "batch.worker.executors.shell")
public class ShellExecutorProperties {

  /** 总开关。默认 false:executor 不会被 Spring 注册,SPI registry 找不到 "shell" type。 */
  private boolean enabled = false;

  /** 程序路径白名单。空列表 = 允许全部(仅 dev / 信任环境推荐)。生产推荐显式列举。 */
  private Set<String> commandWhitelist = Set.of();

  /** 工作目录基础路径,每次执行在它下面创建唯一子目录。默认使用 JVM 临时目录下的 batch-shell。 */
  private Path workdirBase = Path.of(System.getProperty("java.io.tmpdir"), "batch-shell");

  /** 默认超时。业务 parameters.timeoutSeconds 可缩短不可延长。 */
  private Duration defaultTimeout = Duration.ofMinutes(5);

  /** stdout 截断字节数,超出丢弃 + log WARN。默认 1MB。 */
  private int maxStdoutBytes = 1024 * 1024;

  /** stderr 截断字节数,超出丢弃 + log WARN。默认 256KB。 */
  private int maxStderrBytes = 256 * 1024;

  /** 允许透传的环境变量名白名单。默认空 = 不透传任何 env(executor 自己注入 tenantId/jobCode 等)。 */
  private Set<String> allowedEnvKeys = Set.of();

  /** 给 shell 任务挂的 task type 标识。固定 "shell",留可配置只为测试。 */
  private String taskType = "shell";

  /** 执行后是否清理 workdir。默认 true(留 false 便于排查)。 */
  private boolean cleanupWorkdir = true;

  /** 程序的运行用户。null = 当前进程用户;非 null 需要外层 sudo / pam 配合。 */
  private String runAsUser = null;

  /** 内部:命令分隔后允许的最长 args 数,防 fork bomb。 */
  private int maxArgs = 64;

  /** 允许的字符集白名单(command + args 必须命中)。默认 POSIX 友好 + 路径 + 数字字母连字符。 */
  private List<String> argRegexAllowlist = List.of("^[\\w\\-./@= :+,]*$");

  /**
   * 是否拒绝含 {@code ..} 父目录引用的 command / args(防路径穿越,如 {@code ../../etc/passwd})。默认 true。
   *
   * <p>只针对真正的 {@code ..} 路径段,合法子串(如 {@code foo..bar})不受影响。
   */
  private boolean rejectParentDirRefs = true;
}
