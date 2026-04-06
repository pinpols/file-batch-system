package com.example.batch.worker.exports.config;

import com.example.batch.common.config.MinioStorageProperties;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class MinioConfiguration {

    private final MinioStorageProperties minioStorageProperties;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(minioStorageProperties.getEndpoint())
                .credentials(minioStorageProperties.getAccessKey(), minioStorageProperties.getSecretKey())
                .build();
    }
}
