package com.example.batch.trigger;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.example.batch.trigger.mapper")
public class BatchTriggerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BatchTriggerApplication.class, args);
    }
}
