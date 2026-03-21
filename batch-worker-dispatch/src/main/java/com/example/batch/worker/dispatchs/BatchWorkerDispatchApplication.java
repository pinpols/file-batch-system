package com.example.batch.worker.dispatchs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.example.batch")
@EnableScheduling
@ConfigurationPropertiesScan(basePackages = "com.example.batch")
public class BatchWorkerDispatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(BatchWorkerDispatchApplication.class, args);
    }
}
