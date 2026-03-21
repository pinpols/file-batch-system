package com.example.batch.orchestrator;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.example.batch.orchestrator.mapper")
@ConfigurationPropertiesScan("com.example.batch.orchestrator.config")
@EnableScheduling
public class BatchOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(BatchOrchestratorApplication.class, args);
    }
}
