package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.example.batch.worker.dispatchs.config.MinioStorageProperties;
import com.example.batch.worker.dispatchs.infrastructure.DispatchFileContentResolver;
import io.minio.MinioClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
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
            this.minioClient = MinioClient.builder()
                    .endpoint(minioStorageProperties.getEndpoint())
                    .credentials(minioStorageProperties.getAccessKey(), minioStorageProperties.getSecretKey())
                    .build();
        }
    }

    @Override
    public boolean supports(String channelType) {
        return channelType != null && "OSS".equalsIgnoreCase(channelType);
    }

    @Override
    public DispatchResult dispatch(DispatchCommand command) {
        return RemoteFilesystemDispatchSupport.dispatchOss(command, contentResolver, minioStorageProperties, minioClient);
    }
}
