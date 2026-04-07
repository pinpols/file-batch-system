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
 * SFTP upload of the file referenced by {@code file_record} to {@code sftp_remote_directory}.
 *
 * <p>Channel config keys:
 * <ul>
 *   <li>{@code sftp_strict_host_key_checking} — "yes" (default, secure) or "no" (insecure, MITM risk)</li>
 *   <li>{@code sftp_known_hosts_path} — path to known_hosts file when strict checking is enabled</li>
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

    @Override
    public DispatchResult dispatch(DispatchCommand command) {
        Map<String, Object> channelConfig = command.channelConfig();
        String host = stringProp(channelConfig, "sftp_host");
        if (!StringUtils.hasText(host)) {
            return new DispatchResult(false, null, null, false, false, "sftp_host missing", null);
        }
        int port = intProp(channelConfig, "sftp_port", 22);
        String user = stringProp(channelConfig, "sftp_user");
        String password = stringProp(channelConfig, "sftp_password");
        if (!StringUtils.hasText(user) || !StringUtils.hasText(password)) {
            return new DispatchResult(false, null, null, false, false, "sftp_user/sftp_password missing", null);
        }
        String remoteDir = stringProp(channelConfig, "sftp_remote_directory");
        if (!StringUtils.hasText(remoteDir)) {
            remoteDir = "/";
        }
        if (!remoteDir.endsWith("/")) {
            remoteDir = remoteDir + "/";
        }
        Map<String, Object> fileRecord = command.fileRecord();
        String remoteName = stringProp(channelConfig, "sftp_remote_file_name");
        if (!StringUtils.hasText(remoteName)) {
            remoteName = firstNonBlank(
                    String.valueOf(fileRecord.getOrDefault("original_file_name", "")),
                    String.valueOf(fileRecord.getOrDefault("file_name", "file.bin"))
            );
        }
        remoteName = sanitizeFileName(remoteName);

        String receiptPolicy = String.valueOf(channelConfig.getOrDefault("receipt_policy", "SYNC"));
        String externalRequestId = command.payload().externalRequestId() != null && !command.payload().externalRequestId().isBlank()
                ? command.payload().externalRequestId()
                : UUID.randomUUID().toString();
        String receiptCode = command.payload().receiptCode() != null && !command.payload().receiptCode().isBlank()
                ? command.payload().receiptCode()
                : "R-" + externalRequestId;
        boolean acknowledged = "NONE".equalsIgnoreCase(receiptPolicy) || "SYNC".equalsIgnoreCase(receiptPolicy);
        boolean pending = "ASYNC".equalsIgnoreCase(receiptPolicy) || "POLLING".equalsIgnoreCase(receiptPolicy);

        Session session = null;
        ChannelSftp sftp = null;
        try {
            JSch jsch = new JSch();
            // H-6: 默认启用主机密钥检查；允许通过 channel 配置显式关闭
            String strictHostKeyChecking = stringProp(channelConfig, "sftp_strict_host_key_checking");
            boolean strictMode = !"no".equalsIgnoreCase(strictHostKeyChecking);
            if (!strictMode) {
                log.warn("SFTP StrictHostKeyChecking disabled for host {} — susceptible to MITM attacks", host);
            } else {
                String knownHostsPath = stringProp(channelConfig, "sftp_known_hosts_path");
                if (StringUtils.hasText(knownHostsPath)) {
                    jsch.setKnownHosts(knownHostsPath);
                }
            }
            session = jsch.getSession(user, host, port);
            // M-8: JSch API 仅支持 String 密码，无法使用 char[] + 显式擦除；生产环境建议改用密钥认证
            session.setPassword(password);
            session.setConfig("StrictHostKeyChecking", strictMode ? "yes" : "no");
            session.connect(30_000);
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(30_000);
            String remotePath = remoteDir + remoteName;
            try (InputStream in = fileContentResolver.openInputStream(fileRecord)) {
                sftp.put(in, remotePath, ChannelSftp.OVERWRITE);
            }
            String evidence = "sftp://" + host + remotePath;
            return new DispatchResult(true, externalRequestId, receiptCode, acknowledged, pending, "uploaded via SFTP", evidence);
        } catch (Exception ex) {
            return new DispatchResult(false, externalRequestId, receiptCode, false, false, ex.getMessage(), null);
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
