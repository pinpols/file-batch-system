package io.github.pinpols.batch.worker.dispatchs;

import io.github.pinpols.batch.common.config.BatchJsonAutoConfiguration;
import io.github.pinpols.batch.common.config.BatchObjectCryptoAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "io.github.pinpols.batch")
@ImportAutoConfiguration({
  BatchJsonAutoConfiguration.class,
  BatchObjectCryptoAutoConfiguration.class,
  RestClientAutoConfiguration.class
})
@EnableKafka
@EnableScheduling
@ConfigurationPropertiesScan(basePackages = "io.github.pinpols.batch")
@MapperScan({
  "io.github.pinpols.batch.worker.core.mapper",
  "io.github.pinpols.batch.worker.dispatchs.mapper",
  "io.github.pinpols.batch.common.mapper"
})
/** 分发 Worker 应用程序入口。 */
public class BatchWorkerDispatchApplication {

  public static void main(String[] args) {
    SpringApplication.run(BatchWorkerDispatchApplication.class, args);
  }
}
