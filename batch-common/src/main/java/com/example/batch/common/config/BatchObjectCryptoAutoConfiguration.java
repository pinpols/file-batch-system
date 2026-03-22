package com.example.batch.common.config;

import com.example.batch.common.service.BatchObjectCryptoService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties({
        BatchSecurityProperties.class,
        BatchKmsProperties.class
})
public class BatchObjectCryptoAutoConfiguration {

    @Bean
    public BatchObjectCryptoService batchObjectCryptoService(BatchSecurityProperties securityProperties,
                                                             BatchKmsProperties kmsProperties) {
        return new BatchObjectCryptoService(securityProperties, kmsProperties);
    }
}
