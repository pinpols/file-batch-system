package com.example.batch.console;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import com.example.batch.common.logging.HttpRequestMdcAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = HttpRequestMdcAutoConfiguration.class)
@MapperScan("com.example.batch.console.mapper")
@ConfigurationPropertiesScan(basePackages = "com.example.batch")
public class BatchConsoleApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(BatchConsoleApiApplication.class, args);
    }
}
