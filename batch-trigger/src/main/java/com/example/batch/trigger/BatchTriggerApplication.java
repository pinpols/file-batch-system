package com.example.batch.trigger;

import com.example.batch.common.config.BatchJsonAutoConfiguration;
import com.example.batch.trigger.config.ShedLockConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootApplication(scanBasePackages = "com.example.batch")
@ImportAutoConfiguration({BatchJsonAutoConfiguration.class, RestClientAutoConfiguration.class})
@Import(ShedLockConfiguration.class)
@MapperScan("com.example.batch.trigger.mapper")
public class BatchTriggerApplication {

  public static void main(String[] args) {
    SpringApplication.run(BatchTriggerApplication.class, args);
  }
}
