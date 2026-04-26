package com.example.batch.trigger;

import com.example.batch.common.config.BatchJsonAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;

// 注:trigger.config.ShedLockConfiguration 由 @SpringBootApplication 默认 ComponentScan 自动装配,
// 无需 @Import。生产 profile(lazy=false)启动期 eager 实例化;本地 profile(lazy=true)整体
// 静默不创建,@Scheduled / @SchedulerLock 在 local 都不工作 — 仅本地单实例调试受影响,生产无碍。
@SpringBootApplication(scanBasePackages = "com.example.batch")
@ImportAutoConfiguration({BatchJsonAutoConfiguration.class, RestClientAutoConfiguration.class})
@MapperScan("com.example.batch.trigger.mapper")
public class BatchTriggerApplication {

  public static void main(String[] args) {
    SpringApplication.run(BatchTriggerApplication.class, args);
  }
}
