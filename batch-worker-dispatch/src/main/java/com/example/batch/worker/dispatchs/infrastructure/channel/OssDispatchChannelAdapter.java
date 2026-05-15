package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.example.batch.common.config.MinioStorageProperties;
import com.example.batch.worker.dispatchs.infrastructure.DispatchFileContentResolver;
import io.minio.MinioClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
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
  private MinioClient minioClient;

  @PostConstruct
  void init() {
    if (minioStorageProperties != null
        && minioStorageProperties.getEndpoint() != null
        && !minioStorageProperties.getEndpoint().isBlank()
        && minioStorageProperties.getAccessKey() != null
        && !minioStorageProperties.getAccessKey().isBlank()
        && minioStorageProperties.getSecretKey() != null
        && !minioStorageProperties.getSecretKey().isBlank()) {
      this.minioClient =
          MinioClient.builder()
              .endpoint(minioStorageProperties.getEndpoint())
              .credentials(
                  minioStorageProperties.getAccessKey(), minioStorageProperties.getSecretKey())
              .build();
    }
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
