package com.example.batch.worker.exports;

import com.example.batch.common.config.BatchJsonAutoConfiguration;
import com.example.batch.common.config.BatchObjectCryptoAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;

@SpringBootApplication(
        scanBasePackages = "com.example.batch",
        exclude = MybatisAutoConfiguration.class)
@ImportAutoConfiguration({
        BatchJsonAutoConfiguration.class,
        BatchObjectCryptoAutoConfiguration.class,
        RestClientAutoConfiguration.class
})
@EnableKafka
@EnableScheduling
@ConfigurationPropertiesScan(basePackages = "com.example.batch")
@MapperScan(basePackages = "com.example.batch.worker.core.mapper", sqlSessionFactoryRef = "exportPlatformSqlSessionFactory")
/**
 * 批量导出 Worker 服务启动类。
 */
public class BatchWorkerExportApplication {

    /**
     * 导出 Worker 服务启动入口。
     */
    public static void main(String[] args) {
        SpringApplication.run(BatchWorkerExportApplication.class, args);
    }
}
