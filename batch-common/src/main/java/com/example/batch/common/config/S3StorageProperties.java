package com.example.batch.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对象存储（S3 协议）连接配置。绑定前缀 {@code batch.storage.s3}。
 *
 * <p>底层用 AWS SDK for Java v2（S3 协议通用客户端），故同一套配置可接 自建 MinIO / Ceph、AWS S3、阿里云 OSS（S3 兼容）、腾讯云 COS（S3
 * 兼容）等——换后端只改本配置，不换 SDK、不改业务代码。
 */
@Data
@ConfigurationProperties(prefix = "batch.storage.s3")
public class S3StorageProperties {

  private String endpoint;
  private String accessKey;
  private String secretKey;
  private String bucket;

  /**
   * S3 区域。AWS / 阿里 OSS / 腾讯 COS 走 SigV4 签名时必填（如 {@code us-east-1} / {@code oss-cn-hangzhou} /
   * {@code ap-guangzhou}）；自建 MinIO / Ceph 可留空。空时不向 client 传 region（保持 MinIO 默认行为）。
   */
  private String region;

  /**
   * 是否在 bucket 不存在时自动创建。自建 MinIO 默认 {@code true}（开发期便利）。 托管云（S3/OSS/COS）bucket 通常已预建、凭据无
   * CreateBucket 权限，应设 {@code false}，否则启动期建桶会撞 AccessDenied。
   */
  private boolean autoCreateBucket = true;

  /** 建立 TCP 连接超时（ms）。后端挂/不可达时快速失败，不拖慢 worker 线程。 */
  private long connectTimeoutMs = 5000L;

  /**
   * socket 空闲超时（ms）。Apache HTTP client 的 {@code socketTimeout} 对读写统一生效——大文件 put/get 时 socket
   * 空闲超过此值即断,无独立的 write timeout 可配。
   */
  private long readTimeoutMs = 30000L;

  /** 大对象上传是否启用 multipart。 */
  private boolean multipartEnabled = true;

  /** 文件大小达到该阈值后使用 multipart。默认 64MiB。 */
  private long multipartThresholdBytes = 64L * 1024 * 1024;

  /** multipart 单 part 大小。S3 要求非最后 part 至少 5MiB，默认 16MiB。 */
  private int multipartPartSizeBytes = 16 * 1024 * 1024;

  /** 客户端最大尝试次数(含首次)。{@code <=0} 表示不覆盖、走 AWS SDK 默认(standard 模式 3 次)。批量洪峰下可调高扛瞬时 503/限流。 */
  private int maxAttempts = 0;

  /**
   * 是否用自适应重试模式(client-side rate limiting,遇限流主动退避)。批处理高并发打对象存储时建议开;默认 false(走标准 指数退避)。仅在 {@link
   * #maxAttempts} {@code >0} 或本项为 true 时才覆盖 SDK 默认重试策略。
   */
  private boolean adaptiveRetry = false;
}
