package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.example.batch.worker.dispatchs.infrastructure.DispatchFileContentResolver;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
      if (!StringUtils.hasText(host)) {
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

    return uploadViaSftp(
        new SftpUploadContext(
            channelConfig,
            connConfig,
            remoteTarget,
            command.fileRecord(),
            externalRequestId,
            receiptCode,
            acknowledged,
            pending));
  }

  private ConnectionConfig resolveConnectionConfig(Map<String, Object> channelConfig) {
    String host = stringProp(channelConfig, "sftp_host");
    if (!StringUtils.hasText(host)) {
      return null;
    }
    int port = intProp(channelConfig, "sftp_port", 22);
    String user = stringProp(channelConfig, "sftp_user");
    String password = stringProp(channelConfig, "sftp_password");
    if (!StringUtils.hasText(user) || !StringUtils.hasText(password)) {
      return null;
    }
    return new ConnectionConfig(host, port, user, password);
  }

  private RemoteTarget resolveRemoteTarget(
      Map<String, Object> channelConfig, Map<String, Object> fileRecord) {
    String remoteDir = stringProp(channelConfig, "sftp_remote_directory");
    if (!StringUtils.hasText(remoteDir)) {
      remoteDir = "/";
    }
    if (!remoteDir.endsWith("/")) {
      remoteDir = remoteDir + "/";
    }
    String remoteName = stringProp(channelConfig, "sftp_remote_file_name");
    if (!StringUtils.hasText(remoteName)) {
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
      // H-6: 默认启用主机密钥检查；允许通过 channel 配置显式关闭
      String strictHostKeyChecking =
          stringProp(ctx.channelConfig(), "sftp_strict_host_key_checking");
      boolean strictMode = !"no".equalsIgnoreCase(strictHostKeyChecking);
      if (!strictMode) {
        log.warn(
            "SFTP StrictHostKeyChecking disabled for host {} — susceptible to MITM attacks",
            ctx.connConfig().host());
      } else {
        String knownHostsPath = stringProp(ctx.channelConfig(), "sftp_known_hosts_path");
        if (StringUtils.hasText(knownHostsPath)) {
          jsch.setKnownHosts(knownHostsPath);
        }
      }
      session =
          jsch.getSession(
              ctx.connConfig().user(), ctx.connConfig().host(), ctx.connConfig().port());
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
      if (sftp != null) {
        try {
          sftp.disconnect();
        } catch (Exception ignored) {
        }
      }
      if (session != null) {
        try {
          session.disconnect();
        } catch (Exception ignored) {
        }
      }
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
    if (v != null && StringUtils.hasText(String.valueOf(v))) {
      return Integer.parseInt(String.valueOf(v).trim());
    }
    return def;
  }

  private static String firstNonBlank(String a, String b) {
    if (StringUtils.hasText(a)) {
      return a.trim();
    }
    if (StringUtils.hasText(b)) {
      return b.trim();
    }
    return "file.bin";
  }

  private static String sanitizeFileName(String name) {
    String n = name.replace("..", "_").replace('/', '_').replace('\\', '_');
    return n.isBlank() ? "file.bin" : n;
  }
}
