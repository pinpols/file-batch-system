package com.example.batch.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.storage.minio")
public class MinioStorageProperties {

  private String endpoint;
  private String accessKey;
  private String secretKey;
  private String bucket;

  /** 建立 TCP 连接超时（ms）。MinIO 挂/不可达时快速失败，不拖慢 worker 线程。 */
  private long connectTimeoutMs = 5000L;

  /** 读取响应超时（ms）。大文件 put/get 时 socket 空闲超过此值即断。 */
  private long readTimeoutMs = 30000L;

  /** 写入请求超时（ms）。同上语义。 */
  private long writeTimeoutMs = 30000L;
}
