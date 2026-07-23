package io.github.pinpols.batch.trigger;

import io.github.pinpols.batch.common.config.BatchJsonAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 注:trigger.config.ShedLockConfiguration 由 @SpringBootApplication 默认 ComponentScan 自动装配,
// 无需 @Import。application-local 已 lazy-initialization=false，确保 TriggerOutboxRelay(@PostConstruct)
// 与 @Scheduled/@SchedulerLock 路径在本地也 eager 生效。
//
// 排除 UserDetailsServiceAutoConfiguration:trigger 走 InternalSecretFilter 共享密钥认证,
// 不挂任何 UserDetailsManager bean。autoconfig 会在缺省时生成 "Using generated security
// password: ..." 启动 WARN — 噪声且可能误导。
// @EnableScheduling:TriggerReconciler / TriggerOutboxRelay 等周期任务使用 Spring @Scheduled。
@SpringBootApplication(
    scanBasePackages = "io.github.pinpols.batch",
    exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@ImportAutoConfiguration({BatchJsonAutoConfiguration.class, RestClientAutoConfiguration.class})
@MapperScan({"io.github.pinpols.batch.trigger.mapper", "io.github.pinpols.batch.common.mapper"})
public class BatchTriggerApplication {

  public static void main(String[] args) {
    SpringApplication.run(BatchTriggerApplication.class, args);
  }
}
