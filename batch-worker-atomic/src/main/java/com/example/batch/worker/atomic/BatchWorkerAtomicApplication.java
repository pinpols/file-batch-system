package com.example.batch.worker.atomic;

import com.example.batch.common.config.BatchJsonAutoConfiguration;
import com.example.batch.common.config.BatchObjectCryptoAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 专用 Task SPI Worker 启动类。
 *
 * <p>独占 shell / sql / stored-proc / http 原子任务执行器(代码已从 worker-core 迁入本模块的 spi.* 子包), 不带任何文件
 * pipeline。复用 worker-core 运行时(CLAIM / lease / report / dispatch-consumer)。
 *
 * <p>安全定位:dual-use(RCE 级)能力隔离到本最小权限进程。部署时应配独立低权限 datasource(不连业务库)、 独立 K8s serviceaccount / 网络策略。见
 * docs/adr/ADR-029-dedicated-spi-worker.md。
 *
 * <p>只 MapperScan 平台运行时 mapper(worker-core + common),无业务 mapper —— 本 worker 不碰业务表。
 */
@SpringBootApplication(scanBasePackages = "com.example.batch")
@ImportAutoConfiguration({
  BatchJsonAutoConfiguration.class,
  BatchObjectCryptoAutoConfiguration.class,
  RestClientAutoConfiguration.class
})
@EnableKafka
@EnableScheduling
@ConfigurationPropertiesScan(basePackages = "com.example.batch")
@MapperScan({"com.example.batch.worker.core.mapper", "com.example.batch.common.mapper"})
public class BatchWorkerAtomicApplication {

  public static void main(String[] args) {
    SpringApplication.run(BatchWorkerAtomicApplication.class, args);
  }
}
