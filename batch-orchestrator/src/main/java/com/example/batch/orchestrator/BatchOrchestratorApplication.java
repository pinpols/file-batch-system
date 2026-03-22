package com.example.batch.orchestrator;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.example.batch.orchestrator.mapper")
@ConfigurationPropertiesScan(basePackages = "com.example.batch")
@EnableScheduling
public class BatchOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(BatchOrchestratorApplication.class, args);
    }
}
