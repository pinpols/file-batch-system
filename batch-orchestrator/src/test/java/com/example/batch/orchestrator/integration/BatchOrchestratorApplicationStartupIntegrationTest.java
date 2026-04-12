package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * 冒烟集成测试：使用真实 Postgres（平台 + 业务）、Kafka 和 MinIO 的 Spring 上下文。
 *
 * <p>继承 {@link AbstractIntegrationTest} —— 不要在此重复 {@code @BatchIntegrationTest} 或容器配置。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BatchOrchestratorApplicationStartupIntegrationTest extends AbstractIntegrationTest {

  @Autowired ApplicationContext applicationContext;

  @Test
  void contextLoads() {
    assertThat(applicationContext).isNotNull();
  }
}
