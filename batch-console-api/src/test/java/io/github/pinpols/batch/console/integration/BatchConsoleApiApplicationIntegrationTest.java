package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.http.OutboundHttpTransport;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.support.http.OkHttpConsoleExternalHttpTransport;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** 使用真实 Postgres、Kafka、对象存储的控制台 API 测试；Flyway 在平台库上执行编排器 {@code db/migration} 迁移。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("控制台 API 集成基座: 上下文可启动,外部 HTTP 通道装配为既定实现")
class BatchConsoleApiApplicationIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  ApplicationContext applicationContext;

  @Test
  @DisplayName("应用启动: 上下文加载成功,外部 HTTP 通道装配为既定实现")
  void shouldLoadContextAndWireHttpTransport_whenApplicationStarts() {
    assertThat(applicationContext).isNotNull();
    assertThat(applicationContext.getBean(OutboundHttpTransport.class))
        .isInstanceOf(OkHttpConsoleExternalHttpTransport.class);
  }
}
