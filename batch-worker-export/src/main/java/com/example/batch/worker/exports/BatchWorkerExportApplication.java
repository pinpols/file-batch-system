package com.example.batch.worker.exports;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.example.batch")
@EnableScheduling
@ConfigurationPropertiesScan(basePackages = "com.example.batch")
@MapperScan("com.example.batch.worker.core.mapper")
public class BatchWorkerExportApplication {

    public static void main(String[] args) {
        SpringApplication.run(BatchWorkerExportApplication.class, args);
    }
}
