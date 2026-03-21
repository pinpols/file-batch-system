package com.example.batch.worker.exports;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.example.batch")
@EnableScheduling
@ConfigurationPropertiesScan(basePackages = "com.example.batch")
public class BatchWorkerExportApplication {

    public static void main(String[] args) {
        SpringApplication.run(BatchWorkerExportApplication.class, args);
    }
}
