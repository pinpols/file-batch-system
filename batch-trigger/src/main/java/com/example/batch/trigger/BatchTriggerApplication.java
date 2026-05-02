package com.example.batch.trigger;

import com.example.batch.common.config.BatchJsonAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// 注:trigger.config.ShedLockConfiguration 由 @SpringBootApplication 默认 ComponentScan 自动装配,
// 无需 @Import。生产 profile(lazy=false)启动期 eager 实例化;本地 profile(lazy=true)整体
// 静默不创建,@Scheduled / @SchedulerLock 在 local 都不工作 — 仅本地单实例调试受影响,生产无碍。
//
// 排除 UserDetailsServiceAutoConfiguration:trigger 走 InternalSecretFilter 共享密钥认证,
// 不挂任何 UserDetailsManager bean。autoconfig 会在缺省时生成 "Using generated security
// password: ..." 启动 WARN — 噪声且可能误导。
@SpringBootApplication(
    scanBasePackages = "com.example.batch",
    exclude = UserDetailsServiceAutoConfiguration.class)
@ImportAutoConfiguration({BatchJsonAutoConfiguration.class, RestClientAutoConfiguration.class})
@MapperScan({"com.example.batch.trigger.mapper", "com.example.batch.common.mapper"})
public class BatchTriggerApplication {

  public static void main(String[] args) {
    SpringApplication.run(BatchTriggerApplication.class, args);
  }
}
