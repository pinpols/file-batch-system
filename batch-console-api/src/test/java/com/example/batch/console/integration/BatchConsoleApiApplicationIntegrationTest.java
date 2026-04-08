package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.console.BatchConsoleApiApplication;
import com.example.batch.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * 使用真实 Postgres、Kafka、MinIO 的控制台 API 测试；Flyway 在平台库上执行编排器 {@code db/migration} 迁移。
 */
@SpringBootTest(classes = BatchConsoleApiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class BatchConsoleApiApplicationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ApplicationContext applicationContext;

    @Test
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
    }
}
