package io.github.pinpols.batch.ext.sftp;

import io.github.pinpols.batch.common.spi.task.BatchTaskExecutor;
import io.github.pinpols.batch.common.spi.task.ResourceKind;
import io.github.pinpols.batch.common.spi.task.TaskCapability;
import io.github.pinpols.batch.common.spi.task.TaskContext;
import io.github.pinpols.batch.common.spi.task.TaskResult;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * SFTP push 任务示范实现 — Phase 4 第三方 jar 插件模板。
 *
 * <p><b>本类是 stub:真 SFTP 连接 / 文件传输需要替换 {@link #doSftpPush}</b> 用 JSch / Apache MINA SSHD-SFTP /
 * 其它 SFTP 库实现。当前 stub 只校验参数 + 返回 mock 元数据,证明 SPI 注册链路通。
 *
 * <p>本类不引 Spring,完全 POJO。注册由 {@code
 * META-INF/services/io.github.pinpols.batch.common.spi.task.BatchTaskExecutor} 文件声明,
 * worker 启动期 ServiceLoader 自动加载。
 *
 * <p>parameters 协议(jobDefinition.parameters):
 *
 * <ul>
 *   <li>{@code host} (required, String):SFTP 服务器
 *   <li>{@code port} (optional, Integer, default 22):端口
 *   <li>{@code username} (required, String)
 *   <li>{@code password} (optional, String):密码(跟 privateKey 二选一)
 *   <li>{@code privateKey} (optional, String):SSH 私钥(跟 password 二选一)
 *   <li>{@code localPath} (required, String):本地源文件路径
 *   <li>{@code remotePath} (required, String):远程目标路径
 * </ul>
 *
 * <p>output 协议:
 *
 * <ul>
 *   <li>{@code bytesTransferred} (Long)
 *   <li>{@code durationMillis} (Long)
 *   <li>{@code remotePath} (String)
 * </ul>
 *
 * <p>真做 SFTP 时需补:host key verification / connection pooling / retry / 文件 checksum 校验 等。
 */
public class SftpPushTaskExecutor implements BatchTaskExecutor {

  public static final String TASK_TYPE = "sftp_push";

  static final String PARAM_HOST = "host";
  static final String PARAM_PORT = "port";
  static final String PARAM_USER = "username";
  static final String PARAM_PASSWORD = "password";
  static final String PARAM_PRIVATE_KEY = "privateKey";
  static final String PARAM_LOCAL_PATH = "localPath";
  static final String PARAM_REMOTE_PATH = "remotePath";

  @Override
  public String taskType() {
    return TASK_TYPE;
  }

  @Override
  public TaskCapability capability() {
    return new TaskCapability(
        Set.of(ResourceKind.NET, ResourceKind.DISK),
        false, // 推送不视为幂等(目标文件可能被外部覆盖)
        false, // SFTP 客户端通常不支持中途取消(连接是一次性事务)
        Duration.ofMinutes(10));
  }

  @Override
  public TaskResult execute(TaskContext ctx) {
    try {
      SftpInvocation inv = parseInvocation(ctx);
      return doSftpPush(ctx, inv);
    } catch (IllegalArgumentException ex) {
      return TaskResult.fail(ex.getMessage());
    } catch (RuntimeException ex) {
      return TaskResult.fail(ex);
    }
  }

  private static SftpInvocation parseInvocation(TaskContext ctx) {
    Map<String, Object> p = ctx.parameters();

    String host = requireString(p, PARAM_HOST);
    int port = p.get(PARAM_PORT) instanceof Number n ? n.intValue() : 22;
    String user = requireString(p, PARAM_USER);
    String localPath = requireString(p, PARAM_LOCAL_PATH);
    String remotePath = requireString(p, PARAM_REMOTE_PATH);
    Object pwd = p.get(PARAM_PASSWORD);
    Object key = p.get(PARAM_PRIVATE_KEY);
    if (pwd == null && key == null) {
      throw new IllegalArgumentException("parameters.password or parameters.privateKey required");
    }

    return new SftpInvocation(
        host, port, user, Objects.toString(pwd, null), Objects.toString(key, null), localPath, remotePath);
  }

  private static String requireString(Map<String, Object> p, String key) {
    Object v = p.get(key);
    if (!(v instanceof String) || ((String) v).isBlank()) {
      throw new IllegalArgumentException("parameters." + key + " required (non-blank string)");
    }
    return ((String) v).trim();
  }

  /**
   * 真 SFTP 传输的钩子 — 当前是 stub。实际实现替换为:
   *
   * <pre>{@code
   * JSch jsch = new JSch();
   * if (inv.privateKey() != null) jsch.addIdentity("...", inv.privateKey().getBytes(), null, null);
   * Session session = jsch.getSession(inv.username(), inv.host(), inv.port());
   * if (inv.password() != null) session.setPassword(inv.password());
   * session.setConfig("StrictHostKeyChecking", "yes");  // 生产必须
   * session.connect();
   * ChannelSftp ch = (ChannelSftp) session.openChannel("sftp");
   * ch.connect();
   * try (InputStream in = Files.newInputStream(Path.of(inv.localPath()))) {
   *   ch.put(in, inv.remotePath());
   * }
   * long bytes = Files.size(Path.of(inv.localPath()));
   * ch.disconnect();
   * session.disconnect();
   * return TaskResult.ok("...", Map.of("bytesTransferred", bytes, ...));
   * }</pre>
   */
  protected TaskResult doSftpPush(TaskContext ctx, SftpInvocation inv) {
    long start = System.currentTimeMillis();

    Map<String, Object> output = new HashMap<>();
    output.put("bytesTransferred", 0L);
    output.put("durationMillis", System.currentTimeMillis() - start);
    output.put("remotePath", inv.remotePath());
    output.put("mock", true);

    return TaskResult.ok(
        "MOCK sftp push host=" + inv.host() + " port=" + inv.port()
            + " local=" + inv.localPath() + " remote=" + inv.remotePath()
            + " (stub — replace doSftpPush with real JSch/SSHD impl)",
        output);
  }

  /** 解析后的调用参数 — package-private 让 stub 子类 / 测试可访问。 */
  public record SftpInvocation(
      String host,
      int port,
      String username,
      String password,
      String privateKey,
      String localPath,
      String remotePath) {}
}
