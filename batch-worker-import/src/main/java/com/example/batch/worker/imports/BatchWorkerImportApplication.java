package com.example.batch.worker.imports;

import com.example.batch.common.config.BatchJsonAutoConfiguration;
import com.example.batch.common.config.BatchObjectCryptoAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

// Worker 不拥有平台 schema，flyway.enabled=false；直接排除 autoconfig 跳过条件评估
@SpringBootApplication(
    scanBasePackages = "com.example.batch",
    exclude = {MybatisAutoConfiguration.class, FlywayAutoConfiguration.class})
@ImportAutoConfiguration({
  BatchJsonAutoConfiguration.class,
  BatchObjectCryptoAutoConfiguration.class,
  RestClientAutoConfiguration.class
})
@EnableKafka
@EnableScheduling
@ConfigurationPropertiesScan(basePackages = "com.example.batch")
@MapperScan(
    basePackages = "com.example.batch.worker.core.mapper",
    sqlSessionFactoryRef = "importPlatformSqlSessionFactory")
public class BatchWorkerImportApplication {

  public static void main(String[] args) {
    SpringApplication.run(BatchWorkerImportApplication.class, args);
  }
}
