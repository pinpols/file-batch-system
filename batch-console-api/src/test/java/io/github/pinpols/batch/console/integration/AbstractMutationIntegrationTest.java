package io.github.pinpols.batch.console.integration;

import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Console 写路径(POST/PUT/PATCH/DELETE) 集成测试基类。
 *
 * <p>统一收敛前 9 个 *MutationIntegrationTest 各自维护的 {@code WebTestClient} 构造模板与 {@code JdbcTemplate}
 * 注入,避免改动单个集成测试时 6 个文件同步改;字段下沉到本基类后,子类只需写业务 fixture + 测试用例。
 *
 * <p>用法:
 *
 * <pre>{@code
 * @SpringBootTest(
 *     classes = BatchConsoleApiApplication.class,
 *     webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
 *     properties = {"batch.security.bypass-mode=true", "batch.console.ai.enabled=false"})
 * class ConsoleXxxMutationIntegrationTest extends AbstractMutationIntegrationTest {
 *   @Test
 *   void shouldCreate() { client.post().uri("/api/console/xxx")... }
 * }
 * }</pre>
 *
 * <p>注:本基类**不**强加 {@code @SpringBootTest} —— 子类各自声明启用 properties / classes,避免 Spring context
 * 缓存粒度漂移。WebTestClient response timeout 默认 60s,满足 Mutation IT 包含 Flyway / Kafka topic create 等
 * 启动等待场景。
 */
public abstract class AbstractMutationIntegrationTest extends AbstractIntegrationTest {

  @LocalServerPort protected int port;
  @Autowired protected JdbcTemplate jdbcTemplate;

  protected WebTestClient client;

  @BeforeEach
  void initMutationClient() {
    client =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(60))
            .build();
  }
}
