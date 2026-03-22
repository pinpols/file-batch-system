package com.example.batch.console;

import com.example.batch.common.config.BatchJsonAutoConfiguration;
import com.example.batch.common.config.BatchObjectCryptoAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import com.example.batch.common.logging.HttpRequestMdcAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;

@SpringBootApplication(scanBasePackages = "com.example.batch", exclude = HttpRequestMdcAutoConfiguration.class)
@ImportAutoConfiguration({
        BatchJsonAutoConfiguration.class,
        BatchObjectCryptoAutoConfiguration.class,
        RestClientAutoConfiguration.class
})
@MapperScan("com.example.batch.console.mapper")
@ConfigurationPropertiesScan(basePackages = "com.example.batch")
public class BatchConsoleApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(BatchConsoleApiApplication.class, args);
    }
}
