package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.example.batch.common.config.MinioStorageProperties;
import com.example.batch.worker.dispatchs.infrastructure.DispatchFileContentResolver;
import io.minio.MinioClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * OSS(对象存储)渠道分发适配器,将文件上传至 MinIO / S3 兼容存储桶。
 *
 * <p>{@code @Profile("!local")}:仅 local profile(开发者 IDE 沙箱)让位给 {@link
 * StubRemoteFilesystemDispatchChannelAdapter};test profile 下 IT 通过 MinIOContainer 提供真实
 * OSS,本适配器需要参与才能验端到端。
 */
@Component
@Profile("!local")
@Order(20)
@RequiredArgsConstructor
public class OssDispatchChannelAdapter implements DispatchChannelAdapter {

  private final DispatchFileContentResolver contentResolver;
  private final MinioStorageProperties minioStorageProperties;
  // 复用 MinioAutoConfiguration 装配的中心 client(带 OkHttp 连接/读/写超时 + 连接池);
  // ObjectProvider 惰性取,避免硬依赖——未配 MinIO 时保持 null(同历史行为)。
  private final ObjectProvider<MinioClient> minioClientProvider;
  private MinioClient minioClient;

  @PostConstruct
  void init() {
    // 中心 client 仅在 MinIO 配置有效时由 MinioAutoConfiguration 建出;未配则 getIfAvailable() 返回 null
    // (保持历史"未配置 → minioClient 为 null"语义,下游按 null 判定降级)。
    this.minioClient = minioClientProvider.getIfAvailable();
  }

  @Override
  public boolean supports(String channelType) {
    return channelType != null && "OSS".equalsIgnoreCase(channelType);
  }

  @Override
  public DispatchResult dispatch(DispatchCommand command) {
    return RemoteFilesystemDispatchSupport.dispatchOss(
        command, contentResolver, minioStorageProperties, minioClient);
  }
}
