package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.example.batch.common.config.MinioStorageProperties;
import com.example.batch.common.constants.BatchFileConstants;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.security.DnsResolveGuard;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.dispatchs.infrastructure.DispatchFileContentResolver;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 远程文件系统派发的静态工具类，封装 NAS、OSS、SFTP、SMTP（EMAIL）、HTTP 五种渠道的 文件上传（dispatch*）与连通性探测（probe*）逻辑。
 * 所有方法均为包级静态，不持有状态，由各 ChannelDispatchHandler 按渠道类型调用； {@link #probeChannel} 提供统一入口，依据 channel_type
 * 路由到对应探测实现。
 */
@Slf4j
final class RemoteFilesystemDispatchSupport {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String LOG_CATCH_EXCEPTION = "catch:Exception";
  private static final String KEY_TARGET_ENDPOINT = "target_endpoint";
  private static final String PATH_SEP = "/";
  // R-4.2: NAS 沙箱根目录（可选）。若系统属性 batch.dispatch.nas-sandbox-root 设置，
  // 则所有 NAS dispatch 的 realPath 必须落在该根内，否则拒绝；未设置则仅 WARN 不阻断（兼容模式）。
  // 生产强烈建议设置此属性以彻底关闭 symlink 逃逸攻击面。
  private static final String SANDBOX_ROOT_PROP = "batch.dispatch.nas-sandbox-root";

  private static final int MINIO_PART_SIZE = 10 * 1024 * 1024;

  // Dedup: 每个 configured NAS path 的 symlink WARN 只报一次；否则每次 dispatch 都会刷同样告警
  // （macOS 本地 `/tmp → /private/tmp` 是典型场景）。路径在进程生命周期内稳定，内存开销可忽略。
  private static final ConcurrentMap<String, Boolean> NAS_SYMLINK_WARNED =
      new ConcurrentHashMap<>();

  // R2-P1-10：NAS Files.copy 是阻塞 IO；stale NFS mount → 派发线程永久挂死，circuit breaker 接不到。
  // 把 copy 跑在守护线程 pool 上，主线程 future.get(timeout) 限时等待；超时则 cancel(true) + 抛 IOException。
  // 默认 5 分钟（GB 级文件 + 10 MB/s 慢盘 ~100s 留余量），可被 jvm property 覆盖。
  private static final long NAS_COPY_TIMEOUT_SECONDS =
      Long.getLong("batch.dispatch.nas-copy-timeout-seconds", 300L);
  private static final ExecutorService NAS_COPY_EXECUTOR =
      Executors.newCachedThreadPool(
          new java.util.concurrent.ThreadFactory() {
            private final AtomicLong index = new AtomicLong();

            @Override
            public Thread newThread(Runnable r) {
              Thread t = new Thread(r, "nas-copy-" + index.incrementAndGet());
              t.setDaemon(true);
              return t;
            }
          });

  private RemoteFilesystemDispatchSupport() {}

  /** R2-P1-10：有超时保护的 Files.copy。stale NFS mount 时不会让派发线程永久阻塞。 */
  private static void copyWithTimeout(InputStream in, Path target) throws IOException {
    Future<?> future =
        NAS_COPY_EXECUTOR.submit(
            () -> {
              try {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
              } catch (IOException ioe) {
                // 红线 #5:不抛裸 RuntimeException。UncheckedIOException 是 JDK 为"lambda 内包装受检
                // IOException"准备的语义化类型;它仍是 RuntimeException 子类,下方 ExecutionException
                // 解包分支(cause instanceof RuntimeException && cause.getCause() instanceof IOException)照旧命中。
                throw new UncheckedIOException(ioe);
              }
            });
    try {
      future.get(NAS_COPY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException te) {
      future.cancel(true);
      throw new IOException(
          "NAS Files.copy timed out after "
              + NAS_COPY_TIMEOUT_SECONDS
              + "s — likely stale NFS mount or hung remote",
          te);
    } catch (InterruptedException ie) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new IOException("NAS Files.copy interrupted", ie);
    } catch (java.util.concurrent.ExecutionException ee) {
      Throwable cause = ee.getCause();
      if (cause instanceof IOException ioe) {
        throw ioe;
      }
      if (cause instanceof RuntimeException re && re.getCause() instanceof IOException ioe2) {
        throw ioe2;
      }
      throw new IOException("NAS Files.copy failed", cause == null ? ee : cause);
    }
  }

  static DispatchResult dispatchNas(
      DispatchCommand command, DispatchFileContentResolver contentResolver) {
    try {
      Map<String, Object> channelConfig = command.channelConfig();
      String remoteDir =
          resolveEndpointOrFail(channelConfig, "nas_remote_directory", KEY_TARGET_ENDPOINT);
      if (!Texts.hasText(remoteDir)) {
        return new DispatchResult(
            false, null, null, false, false, "nas_remote_directory missing", null);
      }
      // R-4.2: normalize 只处理 . 和 ..，不解析 symlink；恶意配置 /tmp/link_to_parent
      // 即可绕过路径遍历防线。此处额外解析 realPath，若可选的 sandbox root 配置了
      // 则强制 realPath 必须落在沙箱内。
      Path directory = resolveNasDirectory(remoteDir);
      String externalRequestId = resolveExternalRequestId(command);
      String receiptCode = resolveReceiptCode(command, externalRequestId);
      String remoteName =
          resolveRemoteFileName(channelConfig, command.fileRecord(), "nas_remote_file_name");
      Path target = directory.resolve(remoteName);
      try (InputStream in = contentResolver.openInputStream(command.fileRecord())) {
        copyWithTimeout(in, target);
      }
      return finishResult(
          command, externalRequestId, receiptCode, "uploaded via NAS", target.toString());
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(RemoteFilesystemDispatchSupport.class, LOG_CATCH_EXCEPTION, ex);

      return failResult(command, ex);
    }
  }

  /**
   * R-4.2: 返回安全可写的 NAS 目录 {@link Path}。
   *
   * <p>流程：
   *
   * <ol>
   *   <li>{@code toAbsolutePath().normalize()} 消除 . / ..
   *   <li>{@code Files.createDirectories} 保证存在
   *   <li>{@code toRealPath} 解析 symlink 到真实路径
   *   <li>检查真实路径是否等于 normalize 路径；不同则记 WARN（存在 symlink）
   *   <li>若系统属性 {@code batch.dispatch.nas-sandbox-root} 已配置，强制真实路径必须落在沙箱内
   * </ol>
   *
   * <p>返回后续操作使用的真实路径。
   */
  private static Path resolveNasDirectory(String remoteDir) throws IOException {
    Path directory = Path.of(remoteDir).toAbsolutePath().normalize();
    Files.createDirectories(directory);
    Path realDirectory = directory.toRealPath();
    if (!realDirectory.equals(directory)
        && NAS_SYMLINK_WARNED.putIfAbsent(directory.toString(), Boolean.TRUE) == null) {
      // macOS 系统级 symlink (/tmp -> /private/tmp, /var -> /private/var) 是 OS 行为，
      // 不属于用户错配，降级到 INFO；非此模式才以 WARN 提示真正的可疑 symlink。
      boolean macOsPrivatePrefix =
          realDirectory.toString().equals("/private" + directory)
              && (directory.startsWith("/tmp") || directory.startsWith("/var"));
      if (macOsPrivatePrefix) {
        log.info(
            "NAS directory resolved through macOS /private symlink: configured={}, real={}",
            directory,
            realDirectory);
      } else {
        log.warn(
            "NAS directory contains symlink(s): configured={}, real={} — using real path;"
                + " set -D{}=<abs-path> to enforce sandbox root and reject symlink escape"
                + " (this warning is emitted only once per configured path)",
            directory,
            realDirectory,
            SANDBOX_ROOT_PROP);
      }
    }
    String sandboxRootRaw = System.getProperty(SANDBOX_ROOT_PROP);
    if (Texts.hasText(sandboxRootRaw)) {
      Path sandboxRoot = Path.of(sandboxRootRaw).toAbsolutePath().normalize().toRealPath();
      if (!realDirectory.startsWith(sandboxRoot)) {
        throw new SecurityException(
            "NAS directory escapes sandbox root: real="
                + realDirectory
                + ", sandboxRoot="
                + sandboxRoot);
      }
    }
    return realDirectory;
  }

  static DispatchResult dispatchOss(
      DispatchCommand command,
      DispatchFileContentResolver contentResolver,
      MinioStorageProperties minioProperties,
      MinioClient minioClient) {
    try {
      Map<String, Object> channelConfig = command.channelConfig();
      MinioClient client = minioClient(minioProperties, minioClient);
      String bucket =
          firstText(channelConfig, "oss_bucket", "storage_bucket", minioProperties.getBucket());
      if (!Texts.hasText(bucket)) {
        return new DispatchResult(false, null, null, false, false, "oss_bucket missing", null);
      }
      String objectPrefix = firstText(channelConfig, "oss_object_prefix", KEY_TARGET_ENDPOINT, "");
      String externalRequestId = resolveExternalRequestId(command);
      String receiptCode = resolveReceiptCode(command, externalRequestId);
      String remoteName =
          resolveRemoteFileName(channelConfig, command.fileRecord(), "oss_object_name");
      String objectName = normalizeObjectName(objectPrefix, remoteName);
      String contentType =
          firstText(
              command.fileRecord(), "mime_type", BatchFileConstants.CONTENT_TYPE_OCTET_STREAM);
      try (InputStream in = contentResolver.openInputStream(command.fileRecord())) {
        client.putObject(
            PutObjectArgs.builder().bucket(bucket).object(objectName).stream(
                    in, -1, MINIO_PART_SIZE)
                .contentType(contentType)
                .build());
      }
      return finishResult(
          command,
          externalRequestId,
          receiptCode,
          "uploaded via OSS",
          "oss://" + bucket + PATH_SEP + objectName);
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(RemoteFilesystemDispatchSupport.class, LOG_CATCH_EXCEPTION, ex);

      return failResult(command, ex);
    }
  }

  private static DispatchResult failResult(DispatchCommand command, Exception ex) {
    String externalRequestId = resolveExternalRequestId(command);
    String receiptCode = resolveReceiptCode(command, externalRequestId);
    return new DispatchResult(
        false, externalRequestId, receiptCode, false, false, ex.getMessage(), null);
  }

  static DispatchChannelProbeResult probeNas(Map<String, Object> channelConfig) {
    try {
      String remoteDir =
          resolveEndpointOrFail(channelConfig, "nas_remote_directory", KEY_TARGET_ENDPOINT);
      if (!Texts.hasText(remoteDir)) {
        return new DispatchChannelProbeResult(false, "nas_remote_directory missing", null);
      }
      // R-4.2: probe 与 dispatch 走同一路径解析，保证两者对"可写性"的判断一致，
      // 避免 probe 显示 OK 但 dispatch 被沙箱拒绝（或反之）。
      Path directory = resolveNasDirectory(remoteDir);
      if (!Files.isDirectory(directory)) {
        return new DispatchChannelProbeResult(
            false, "nas path is not a directory: " + directory, null);
      }
      if (!Files.isWritable(directory)) {
        return new DispatchChannelProbeResult(
            false,
            "nas path not writable (read-only mount or permission denied): " + directory,
            null);
      }
      String probeName = BatchFileConstants.newHealthProbeName();
      Path probeFile = directory.resolve(probeName);
      Files.writeString(
          probeFile, "probe@" + BatchDateTimeSupport.utcNow(), StandardCharsets.UTF_8);
      Files.deleteIfExists(probeFile);
      return new DispatchChannelProbeResult(true, "nas probe ok", probeFile.toString());
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(RemoteFilesystemDispatchSupport.class, LOG_CATCH_EXCEPTION, ex);

      return new DispatchChannelProbeResult(false, ex.getMessage(), null);
    }
  }

  static DispatchChannelProbeResult probeOss(
      Map<String, Object> channelConfig,
      MinioStorageProperties minioProperties,
      MinioClient minioClient) {
    try {
      MinioClient client = minioClient(minioProperties, minioClient);
      String bucket =
          firstText(channelConfig, "oss_bucket", "storage_bucket", minioProperties.getBucket());
      if (!Texts.hasText(bucket)) {
        return new DispatchChannelProbeResult(false, "oss_bucket missing", null);
      }
      String prefix = firstText(channelConfig, "oss_object_prefix", KEY_TARGET_ENDPOINT, "");
      String objectName = normalizeObjectName(prefix, BatchFileConstants.newHealthProbeName());
      byte[] payload = ("probe@" + BatchDateTimeSupport.utcNow()).getBytes(StandardCharsets.UTF_8);
      client.putObject(
          PutObjectArgs.builder().bucket(bucket).object(objectName).stream(
                  new ByteArrayInputStream(payload), payload.length, MINIO_PART_SIZE)
              .contentType(BatchFileConstants.CONTENT_TYPE_TEXT_UTF8)
              .build());
      try {
        client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectName).build());
      } finally {
        client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectName).build());
      }
      return new DispatchChannelProbeResult(
          true, "oss probe ok", "oss://" + bucket + PATH_SEP + objectName);
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(RemoteFilesystemDispatchSupport.class, LOG_CATCH_EXCEPTION, ex);

      return new DispatchChannelProbeResult(false, ex.getMessage(), null);
    }
  }

  static DispatchChannelProbeResult probeSftp(
      Map<String, Object> channelConfig, boolean dnsGuardEnabled) {
    try {
      String host = resolveEndpointOrFail(channelConfig, "sftp_host", KEY_TARGET_ENDPOINT);
      if (!Texts.hasText(host)) {
        return new DispatchChannelProbeResult(false, "sftp_host missing", null);
      }
      int port = intProp(channelConfig, "sftp_port", 22);
      // S-2.6: resolve-then-connect — 用已校验的 IP 建连
      InetSocketAddress target =
          dnsGuardEnabled
              ? new InetSocketAddress(DnsResolveGuard.resolveAndValidate(host), port)
              : new InetSocketAddress(host, port);
      try (Socket socket = new Socket()) {
        socket.connect(target, 5_000);
      }
      return new DispatchChannelProbeResult(true, "sftp probe ok", host + ":" + port);
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(RemoteFilesystemDispatchSupport.class, LOG_CATCH_EXCEPTION, ex);

      return new DispatchChannelProbeResult(false, ex.getMessage(), null);
    }
  }

  static DispatchChannelProbeResult probeSmtp(
      Map<String, Object> channelConfig, boolean dnsGuardEnabled) {
    try {
      String host = resolveEndpointOrFail(channelConfig, "smtp_host", KEY_TARGET_ENDPOINT);
      if (!Texts.hasText(host)) {
        return new DispatchChannelProbeResult(false, "smtp_host missing", null);
      }
      int port = intProp(channelConfig, "smtp_port", 25);
      // S-2.6: resolve-then-connect — 用已校验的 IP 建连
      InetSocketAddress target =
          dnsGuardEnabled
              ? new InetSocketAddress(DnsResolveGuard.resolveAndValidate(host), port)
              : new InetSocketAddress(host, port);
      try (Socket socket = new Socket()) {
        socket.connect(target, 5_000);
      }
      return new DispatchChannelProbeResult(true, "smtp probe ok", host + ":" + port);
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(RemoteFilesystemDispatchSupport.class, LOG_CATCH_EXCEPTION, ex);

      return new DispatchChannelProbeResult(false, ex.getMessage(), null);
    }
  }

  static DispatchChannelProbeResult probeHttp(
      Map<String, Object> channelConfig, boolean dnsGuardEnabled) {
    try {
      String endpoint = stringProp(channelConfig, KEY_TARGET_ENDPOINT);
      if (!Texts.hasText(endpoint)) {
        return new DispatchChannelProbeResult(false, "target_endpoint missing", null);
      }
      // S-2.6: resolve-then-connect — 校验目标 IP 后把 HTTP 客户端 DNS 钉到该地址,
      // 与真实 API/API_PUSH dispatch 的 SSRF 防护保持一致。
      OkHttpClient client =
          new OkHttpClient.Builder()
              .connectTimeout(Duration.ofSeconds(5))
              .readTimeout(Duration.ofSeconds(5))
              .build();
      if (dnsGuardEnabled) {
        String probeHost = URI.create(endpoint).getHost();
        var resolved = DnsResolveGuard.resolveAndValidate(probeHost);
        client = client.newBuilder().dns(hostname -> List.of(resolved)).build();
      }
      Request request = new Request.Builder().url(endpoint).head().build();
      try (Response response = client.newCall(request).execute()) {
        int status = response.code();
        if (status >= 200 && status < 500) {
          return new DispatchChannelProbeResult(
              true, "http probe ok (status=" + status + ")", endpoint);
        }
        return new DispatchChannelProbeResult(
            false, "http probe failed (status=" + status + ")", endpoint);
      }
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(RemoteFilesystemDispatchSupport.class, LOG_CATCH_EXCEPTION, ex);

      return new DispatchChannelProbeResult(false, ex.getMessage(), null);
    }
  }

  static DispatchChannelProbeResult probeChannel(
      Map<String, Object> channelConfig,
      MinioStorageProperties minioProperties,
      MinioClient minioClient,
      boolean dnsGuardEnabled) {
    String channelType =
        String.valueOf(channelConfig.getOrDefault("channel_type", "")).toUpperCase(Locale.ROOT);
    return switch (channelType) {
      case "NAS" -> probeNas(channelConfig);
      case "OSS" -> probeOss(channelConfig, minioProperties, minioClient);
      case "SFTP" -> probeSftp(channelConfig, dnsGuardEnabled);
      case "EMAIL" -> probeSmtp(channelConfig, dnsGuardEnabled);
      case "API", "API_PUSH" -> probeHttp(channelConfig, dnsGuardEnabled);
      default ->
          new DispatchChannelProbeResult(
              false, "unsupported health probe channel type: " + channelType, null);
    };
  }

  private static DispatchResult finishResult(
      DispatchCommand command,
      String externalRequestId,
      String receiptCode,
      String message,
      String evidence) {
    Map<String, Object> channelConfig = command.channelConfig();
    String receiptPolicy = String.valueOf(channelConfig.getOrDefault("receipt_policy", "SYNC"));
    boolean acknowledged =
        "NONE".equalsIgnoreCase(receiptPolicy) || "SYNC".equalsIgnoreCase(receiptPolicy);
    boolean pending =
        "ASYNC".equalsIgnoreCase(receiptPolicy) || "POLLING".equalsIgnoreCase(receiptPolicy);
    return new DispatchResult(
        true, externalRequestId, receiptCode, acknowledged, pending, message, evidence);
  }

  private static String resolveExternalRequestId(DispatchCommand command) {
    if (command.payload().externalRequestId() != null
        && !command.payload().externalRequestId().isBlank()) {
      return command.payload().externalRequestId();
    }
    return UUID.randomUUID().toString();
  }

  private static String resolveReceiptCode(DispatchCommand command, String externalRequestId) {
    if (command.payload().receiptCode() != null && !command.payload().receiptCode().isBlank()) {
      return command.payload().receiptCode();
    }
    return "R-" + externalRequestId;
  }

  private static String resolveRemoteFileName(
      Map<String, Object> channelConfig, Map<String, Object> fileRecord, String overrideKey) {
    String explicit = firstText(channelConfig, overrideKey, null);
    if (Texts.hasText(explicit)) {
      return sanitizeFileName(explicit);
    }
    Object original =
        fileRecord == null
            ? null
            : firstNonNull(fileRecord.get("original_file_name"), fileRecord.get("file_name"));
    String fallback =
        original == null ? BatchFileConstants.DEFAULT_FILE_NAME : String.valueOf(original);
    return sanitizeFileName(fallback);
  }

  private static String sanitizeFileName(String name) {
    if (name == null) {
      return BatchFileConstants.DEFAULT_FILE_NAME;
    }
    String n = name.trim().replace("..", "_").replace('/', '_').replace('\\', '_');
    return n.isBlank() ? BatchFileConstants.DEFAULT_FILE_NAME : n;
  }

  private static String normalizeObjectName(String prefix, String name) {
    String p = prefix == null ? "" : prefix.trim();
    if (p.startsWith(PATH_SEP)) {
      p = p.substring(1);
    }
    if (!p.isEmpty() && !p.endsWith(PATH_SEP)) {
      p = p + PATH_SEP;
    }
    return p + sanitizeFileName(name);
  }

  private static String firstText(Map<String, Object> map, String key, String fallback) {
    Object v = map == null ? null : map.get(key);
    if (v == null) {
      return fallback;
    }
    String text = String.valueOf(v).trim();
    return text.isEmpty() ? fallback : text;
  }

  private static String firstText(
      Map<String, Object> map, String key1, String key2, String fallback) {
    String v = firstText(map, key1, null);
    if (Texts.hasText(v)) {
      return v;
    }
    return firstText(map, key2, fallback);
  }

  private static int intProp(Map<String, Object> map, String key, int defaultValue) {
    Object v = map == null ? null : map.get(key);
    if (v == null) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(String.valueOf(v).trim());
    } catch (NumberFormatException e) {
      SwallowedExceptionLogger.info(
          RemoteFilesystemDispatchSupport.class, "catch:NumberFormatException", e);

      return defaultValue;
    }
  }

  /**
   * 渠道配置端点解析：先读 {@code primaryKey}（如 {@code nas_remote_directory} / {@code sftp_host} / {@code
   * smtp_host}），缺失再回退到 {@code fallbackKey}（通常是 {@link #KEY_TARGET_ENDPOINT}）。 消除 NAS dispatch / NAS
   * probe / SFTP probe / SMTP probe 四处重复的 3 行 primary→fallback 读取样板。
   *
   * @return 解析后的 endpoint 字符串；两键都为 null/blank 时返回 {@code null}，由调用方产出带渠道语义的错误消息。
   */
  private static String resolveEndpointOrFail(
      Map<String, Object> channelConfig, String primaryKey, String fallbackKey) {
    String value = stringProp(channelConfig, primaryKey);
    if (Texts.hasText(value)) {
      return value;
    }
    return stringProp(channelConfig, fallbackKey);
  }

  private static String stringProp(Map<String, Object> map, String key) {
    Object v = map == null ? null : map.get(key);
    if (v == null) {
      return null;
    }
    String text = String.valueOf(v).trim();
    return text.isEmpty() ? null : text;
  }

  private static Object firstNonNull(Object a, Object b) {
    return a != null ? a : b;
  }

  private static MinioClient minioClient(
      MinioStorageProperties properties, MinioClient minioClient) {
    if (minioClient != null) {
      return minioClient;
    }
    if (properties == null
        || !Texts.hasText(properties.getEndpoint())
        || !Texts.hasText(properties.getAccessKey())
        || !Texts.hasText(properties.getSecretKey())) {
      throw new IllegalStateException("MinIO not configured");
    }
    return MinioClient.builder()
        .endpoint(properties.getEndpoint())
        .credentials(properties.getAccessKey(), properties.getSecretKey())
        .build();
  }
}
