package com.example.batch.console;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.example.batch.console.mapper")
@ConfigurationPropertiesScan("com.example.batch.console")
public class BatchConsoleApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(BatchConsoleApiApplication.class, args);
    }
}
