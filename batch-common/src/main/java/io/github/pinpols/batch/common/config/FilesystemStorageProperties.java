package io.github.pinpols.batch.common.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文件系统对象存储后端配置（Phase 2 §4 / §8）。绑定前缀 {@code batch.storage.filesystem}。
 *
 * <p>当 {@code batch.storage.backend=filesystem} 时启用：bucket → {@code root/<bucket>/} 目录；key →
 * 该目录下相对路径。
 *
 * <p>多主机部署：所有 worker / orchestrator / console 必须挂载同一个 NAS（同 {@link #root}），否则分区 worker 跨主机互相读不到；
 * 纯本地盘则强制单主机或 {@code partitionCount=1}（见 {@code docs/runbook/object-storage-filesystem.md}）。
 */
@Data
@ConfigurationProperties(prefix = "batch.storage.filesystem")
public class FilesystemStorageProperties {

  /** FS 后端的根目录，绝对路径。bucket 映射为 {@code root/<bucket>/}。 */
  private String root;

  /**
   * FS 模式 presign 返回的应用下载端点 URL 前缀，例如 {@code
   * https://console.internal/api/console/files/fs-download}。 presign 生成的 URL 形如 {@code
   * <downloadBaseUrl>?b=<bucket>&k=<key>&e=<expEpochSec>&s=<sig>}。
   */
  private String downloadBaseUrl;

  /**
   * presign HMAC 密钥。留空时回落到 {@link
   * BatchSecurityProperties#getInternalSecret()}（复用平台现有内部密钥，不引入新密钥体系）。
   */
  private String presignSecret;

  /**
   * presign URL 默认 TTL。FS 端点是能力令牌（不绑 IP / 不含内容哈希），TTL 短为佳；默认 5 分钟。 仅作为回退参考，接口 {@code
   * presign(bucket, key, ttl)} 仍优先用入参。
   */
  private Duration defaultPresignTtl = Duration.ofMinutes(5);
}
