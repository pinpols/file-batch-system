package com.example.batch.trigger;

import com.example.batch.common.config.BatchJsonAutoConfiguration;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;

@SpringBootApplication(scanBasePackages = "com.example.batch")
@ImportAutoConfiguration({BatchJsonAutoConfiguration.class, RestClientAutoConfiguration.class})
@MapperScan("com.example.batch.trigger.mapper")
public class BatchTriggerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BatchTriggerApplication.class, args);
    }
}
