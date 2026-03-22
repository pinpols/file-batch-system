package com.example.batch.worker.dispatchs;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.example.batch")
@EnableScheduling
@ConfigurationPropertiesScan(basePackages = "com.example.batch")
@MapperScan({
        "com.example.batch.worker.core.mapper",
        "com.example.batch.worker.dispatchs.mapper"
})
public class BatchWorkerDispatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(BatchWorkerDispatchApplication.class, args);
    }
}
