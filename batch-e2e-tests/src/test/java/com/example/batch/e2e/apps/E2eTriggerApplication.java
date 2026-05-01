package com.example.batch.e2e.apps;

import com.example.batch.common.config.BatchJsonAutoConfiguration;
import com.example.batch.common.config.BatchObjectCryptoAutoConfiguration;
import com.example.batch.e2e.config.E2eKafkaProducerConfiguration;
import com.example.batch.e2e.config.E2ePlatformDataSourceConfiguration;
import com.example.batch.e2e.config.E2ePlatformMybatisConfiguration;
import com.example.batch.e2e.config.E2eShedLockConfiguration;
import com.example.batch.trigger.BatchTriggerApplication;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ADR-010 Stage 5 Layer 2 scaffold: trigger-only e2e application context. 与 {@link
 * E2eOrchestratorApplication} 同款风格,只 scan trigger 包,避免 worker / orchestrator 包冲突。
 *
 * <p><b>当前状态(2026-04-30)</b>:scaffold 已落地,但本目录下尚未有用例真起本 application —— Layer 1
 * (`TriggerAsyncLaunchE2eIT` 在 batch-trigger 内)已覆盖 trigger 端 fire→Kafka 完整链路,Layer 2 当前 E2E 用
 * orchestrator-only context + 手动 publish Kafka 模拟 trigger,验证 consumer→job_instance leg。 真做
 * trigger+orchestrator 同 JVM 双 ApplicationContext 全链路 E2E 留作 follow-up(双 context bean 命名冲突 +
 * DataSource 双装配等需要细致 scaffold 设计)。
 *
 * <p>未来用例可继承本 scaffold:启 trigger 完整 context(@SchedulerLock / Quartz / wheel / outbox relay 全栈),配合
 * Layer 2 的 orchestrator context 跑端到端。建议两个 context 独立 @SpringBootTest,在测试主类 @BeforeAll 内
 * SpringApplication.run() 拉起 trigger context 共享同一 PG/Kafka container。
 */
@Configuration
@EnableAutoConfiguration(
    exclude = {
      com.example.batch.common.logging.HttpRequestMdcAutoConfiguration.class,
      org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration.class,
      org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class,
      org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration
          .class,
      org.springframework.boot.security.autoconfigure.web.servlet
          .ServletWebSecurityAutoConfiguration.class,
      org.springframework.boot.security.autoconfigure.actuate.web.servlet
          .ManagementWebSecurityAutoConfiguration.class,
    })
@EnableKafka
@Import({
  E2ePlatformDataSourceConfiguration.class,
  E2ePlatformMybatisConfiguration.class,
  E2eShedLockConfiguration.class,
  E2eKafkaProducerConfiguration.class
})
@ComponentScan(
    basePackages = {"com.example.batch.e2e.support", "com.example.batch.trigger"},
    excludeFilters = {
      @ComponentScan.Filter(
          type = FilterType.ASSIGNABLE_TYPE,
          classes = BatchTriggerApplication.class),
      @ComponentScan.Filter(
          type = FilterType.ASSIGNABLE_TYPE,
          classes = E2eTriggerApplication.class)
    })
@ImportAutoConfiguration({
  BatchJsonAutoConfiguration.class,
  BatchObjectCryptoAutoConfiguration.class,
  RestClientAutoConfiguration.class
})
@EnableScheduling
@ConfigurationPropertiesScan(basePackages = "com.example.batch")
@MapperScan(
    basePackages = "com.example.batch.trigger.mapper",
    sqlSessionFactoryRef = "sqlSessionFactory")
public class E2eTriggerApplication {

  public static void main(String[] args) {
    SpringApplication.run(E2eTriggerApplication.class, args);
  }
}
