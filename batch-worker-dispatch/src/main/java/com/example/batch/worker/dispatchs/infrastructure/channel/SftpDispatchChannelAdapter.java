package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.security.DnsResolveGuard;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.dispatchs.infrastructure.DispatchFileContentResolver;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * SFTP 渠道分发适配器，将 {@code file_record} 引用的文件上传到 {@code sftp_remote_directory}。
 *
 * <p>渠道配置关键字：
 *
 * <ul>
 *   <li>{@code sftp_strict_host_key_checking} — "yes"（默认，安全）或 "no"（不安全，存在中间人攻击风险）
 *   <li>{@code sftp_known_hosts_path} — 启用严格主机密钥检查时的 known_hosts 文件路径
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SftpDispatchChannelAdapter implements DispatchChannelAdapter {

  private final DispatchFileContentResolver fileContentResolver;
  private final BatchSecurityProperties securityProperties;
  // S-1.2 a: 读 active profile 判定是否处于生产
  private final Environment environment;

  private boolean isProductionProfile() {
    if (environment == null) {
      return false;
    }
    String[] active = environment.getActiveProfiles();
    if (active == null || active.length == 0) {
      return false;
    }
    return Arrays.stream(active)
        .anyMatch(p -> "prod".equalsIgnoreCase(p) || "production".equalsIgnoreCase(p));
  }

  @Override
  public boolean supports(String channelType) {
    return channelType != null && "SFTP".equalsIgnoreCase(channelType);
  }

  private record ConnectionConfig(String host, int port, String user, String password) {}

  private record RemoteTarget(String remoteDir, String remoteName) {
    String remotePath() {
      return remoteDir + remoteName;
    }
  }

  @Builder
  private record SftpUploadContext(
      Map<String, Object> channelConfig,
      ConnectionConfig connConfig,
      RemoteTarget remoteTarget,
      Map<String, Object> fileRecord,
      String externalRequestId,
      String receiptCode,
      boolean acknowledged,
      boolean pending) {}

  @Override
  public DispatchResult dispatch(DispatchCommand command) {
    Map<String, Object> channelConfig = command.channelConfig();

    ConnectionConfig connConfig = resolveConnectionConfig(channelConfig);
    if (connConfig == null) {
      String host = stringProp(channelConfig, "sftp_host");
      if (!Texts.hasText(host)) {
        return new DispatchResult(false, null, null, false, false, "sftp_host missing", null);
      }
      return new DispatchResult(
          false, null, null, false, false, "sftp_user/sftp_password missing", null);
    }

    RemoteTarget remoteTarget = resolveRemoteTarget(channelConfig, command.fileRecord());

    String receiptPolicy = String.valueOf(channelConfig.getOrDefault("receipt_policy", "SYNC"));
    String externalRequestId =
        command.payload().externalRequestId() != null
                && !command.payload().externalRequestId().isBlank()
            ? command.payload().externalRequestId()
            : UUID.randomUUID().toString();
    String receiptCode =
        command.payload().receiptCode() != null && !command.payload().receiptCode().isBlank()
            ? command.payload().receiptCode()
            : "R-" + externalRequestId;
    boolean acknowledged =
        "NONE".equalsIgnoreCase(receiptPolicy) || "SYNC".equalsIgnoreCase(receiptPolicy);
    boolean pending =
        "ASYNC".equalsIgnoreCase(receiptPolicy) || "POLLING".equalsIgnoreCase(receiptPolicy);

    SftpUploadContext uploadCtx =
        SftpUploadContext.builder()
            .channelConfig(channelConfig)
            .connConfig(connConfig)
            .remoteTarget(remoteTarget)
            .fileRecord(command.fileRecord())
            .externalRequestId(externalRequestId)
            .receiptCode(receiptCode)
            .acknowledged(acknowledged)
            .pending(pending)
            .build();
    return uploadViaSftp(uploadCtx);
  }

  private ConnectionConfig resolveConnectionConfig(Map<String, Object> channelConfig) {
    String host = stringProp(channelConfig, "sftp_host");
    if (!Texts.hasText(host)) {
      return null;
    }
    int port = intProp(channelConfig, "sftp_port", 22);
    String user = stringProp(channelConfig, "sftp_user");
    String password = stringProp(channelConfig, "sftp_password");
    if (!Texts.hasText(user) || !Texts.hasText(password)) {
      return null;
    }
    return new ConnectionConfig(host, port, user, password);
  }

  private RemoteTarget resolveRemoteTarget(
      Map<String, Object> channelConfig, Map<String, Object> fileRecord) {
    String remoteDir = stringProp(channelConfig, "sftp_remote_directory");
    if (!Texts.hasText(remoteDir)) {
      remoteDir = "/";
    }
    if (!remoteDir.endsWith("/")) {
      remoteDir = remoteDir + "/";
    }
    String remoteName = stringProp(channelConfig, "sftp_remote_file_name");
    if (!Texts.hasText(remoteName)) {
      remoteName =
          firstNonBlank(
              String.valueOf(fileRecord.getOrDefault("original_file_name", "")),
              String.valueOf(fileRecord.getOrDefault("file_name", "file.bin")));
    }
    remoteName = sanitizeFileName(remoteName);
    return new RemoteTarget(remoteDir, remoteName);
  }

  private DispatchResult uploadViaSftp(SftpUploadContext ctx) {
    Session session = null;
    ChannelSftp sftp = null;
    try {
      JSch jsch = new JSch();
      // S-1.2 a: 生产 profile 强制 StrictHostKeyChecking=yes，不允许渠道配置翻盘；
      // dev/test/e2e/local 允许通过 channel 配置 sftp_strict_host_key_checking=no 关闭。
      // 判定方式：只要当前有 "prod" / "production" active profile，则 prodMode=true。
      boolean prodMode = isProductionProfile();
      String strictHostKeyChecking =
          stringProp(ctx.channelConfig(), "sftp_strict_host_key_checking");
      boolean strictMode = prodMode || !"no".equalsIgnoreCase(strictHostKeyChecking);
      if (!strictMode) {
        log.warn(
            "SFTP StrictHostKeyChecking disabled for host {} — susceptible to MITM attacks"
                + " (allowed only outside prod profile)",
            ctx.connConfig().host());
      } else {
        if (prodMode && "no".equalsIgnoreCase(strictHostKeyChecking)) {
          log.warn(
              "SFTP channel requested sftp_strict_host_key_checking=no but prod profile"
                  + " forces strict mode — overriding to yes for host {}",
              ctx.connConfig().host());
        }
        String knownHostsPath = stringProp(ctx.channelConfig(), "sftp_known_hosts_path");
        if (Texts.hasText(knownHostsPath)) {
          jsch.setKnownHosts(knownHostsPath);
        }
      }
      // S-2.6: resolve-then-connect — 用解析后的 IP 建连，防止 DNS rebinding
      String connectHost = ctx.connConfig().host();
      if (!securityProperties.isBypassMode()) {
        InetAddress resolved = DnsResolveGuard.resolveAndValidate(connectHost);
        connectHost = resolved.getHostAddress();
      }
      session = jsch.getSession(ctx.connConfig().user(), connectHost, ctx.connConfig().port());
      // 保留原始 hostname 用于 StrictHostKeyChecking 匹配 known_hosts
      session.setConfig("HostKeyAlias", ctx.connConfig().host());
      // M-8: JSch API 仅支持 String 密码，无法使用 char[] + 显式擦除；生产环境建议改用密钥认证
      session.setPassword(ctx.connConfig().password());
      session.setConfig("StrictHostKeyChecking", strictMode ? "yes" : "no");
      session.connect(30_000);
      sftp = (ChannelSftp) session.openChannel("sftp");
      sftp.connect(30_000);
      String remotePath = ctx.remoteTarget().remotePath();
      try (InputStream in = fileContentResolver.openInputStream(ctx.fileRecord())) {
        sftp.put(in, remotePath, ChannelSftp.OVERWRITE);
      }
      String evidence = "sftp://" + ctx.connConfig().host() + remotePath;
      return new DispatchResult(
          true,
          ctx.externalRequestId(),
          ctx.receiptCode(),
          ctx.acknowledged(),
          ctx.pending(),
          "uploaded via SFTP",
          evidence);
    } catch (Exception ex) {
      return new DispatchResult(
          false, ctx.externalRequestId(), ctx.receiptCode(), false, false, ex.getMessage(), null);
    } finally {
      // D-1：JSch disconnect 在网络抖动 / 半关闭连接上可能挂住 30+s（TCP 关半开），
      // 之前的 try/catch(ignored) 虽然吞了异常但不管挂住——dispatch worker 线程被卡住，
      // 线程池堆积僵尸。改异步 disconnect + 5s 硬超时；超时则让后台线程自然结束，
      // 主路径立即返回不阻塞下一个 dispatch。
      disconnectWithTimeout(sftp, "sftp-channel", ctx.connConfig().host());
      disconnectWithTimeout(session, "sftp-session", ctx.connConfig().host());
    }
  }

  // D-1：用 daemon 单线程池承载异步 disconnect；daemon=true 保证 JVM 退出不被卡住
  private static final ScheduledExecutorService DISCONNECT_EXECUTOR =
      Executors.newScheduledThreadPool(
          2,
          new ThreadFactory() {
            private int n = 0;

            @Override
            public Thread newThread(Runnable r) {
              Thread t = new Thread(r, "sftp-disconnect-" + (++n));
              t.setDaemon(true);
              return t;
            }
          });

  private static void disconnectWithTimeout(ChannelSftp channel, String kind, String host) {
    if (channel == null) {
      return;
    }
    Future<?> future = DISCONNECT_EXECUTOR.submit(channel::disconnect);
    awaitOrCancel(future, kind, host);
  }

  private static void disconnectWithTimeout(Session session, String kind, String host) {
    if (session == null) {
      return;
    }
    Future<?> future = DISCONNECT_EXECUTOR.submit(session::disconnect);
    awaitOrCancel(future, kind, host);
  }

  private static void awaitOrCancel(Future<?> future, String kind, String host) {
    try {
      future.get(5, TimeUnit.SECONDS);
    } catch (TimeoutException timeout) {
      log.warn(
          "{} disconnect timed out for host {} — leaving background thread to finish", kind, host);
      future.cancel(true);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      future.cancel(true);
    } catch (Exception ignored) {
      // 其他 disconnect 异常吞掉（原语义）
    }
  }

  private static String stringProp(Map<String, Object> map, String key) {
    Object v = map == null ? null : map.get(key);
    return v == null ? null : String.valueOf(v);
  }

  private static int intProp(Map<String, Object> map, String key, int def) {
    Object v = map == null ? null : map.get(key);
    if (v instanceof Number n) {
      return n.intValue();
    }
    if (v != null && Texts.hasText(String.valueOf(v))) {
      return Integer.parseInt(String.valueOf(v).trim());
    }
    return def;
  }

  private static String firstNonBlank(String a, String b) {
    if (Texts.hasText(a)) {
      return a.trim();
    }
    if (Texts.hasText(b)) {
      return b.trim();
    }
    return "file.bin";
  }

  private static String sanitizeFileName(String name) {
    String n = name.replace("..", "_").replace('/', '_').replace('\\', '_');
    return n.isBlank() ? "file.bin" : n;
  }
}
