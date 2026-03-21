package com.example.batch.worker.imports;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.example.batch")
@EnableScheduling
@ConfigurationPropertiesScan(basePackages = "com.example.batch")
public class BatchWorkerImportApplication {

    public static void main(String[] args) {
        SpringApplication.run(BatchWorkerImportApplication.class, args);
    }
}
