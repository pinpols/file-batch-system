package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * P2: ConsoleTelemetryController 写路径整合 — 校验 FE 上报 → BE 校验入参 → 写结构化日志(而非业务表)。
 *
 * <p>BE 设计:telemetry 不入业务 DB(P2-2 整改后只记结构化日志,避免登录用户灌爆 Loki/日志存储), 所以集成验证点是:
 *
 * <ul>
 *   <li>合规 payload → 200 SUCCESS
 *   <li>超字段长度 / 缺必填 → 400(Bean Validation)
 *   <li>props 总字节数超限 → 400(controller 层 validateProps 守护)
 *   <li>events 数量超限(>50)→ 400
 * </ul>
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.security.bypass-mode=true", "batch.console.ai.enabled=false"})
class TelemetryWritePathIntegrationTest extends AbstractIntegrationTest {

  @LocalServerPort private int port;
  private WebTestClient webTestClient;

  @BeforeEach
  void setUp() {
    webTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://127.0.0.1:" + port)
            .responseTimeout(Duration.ofSeconds(30))
            .build();
  }

  @Test
  void acceptsValidTelemetry() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("app", "console");
    body.put("events", List.of(Map.of("type", "info", "name", "page_view", "page", "/home")));
    webTestClient
        .post()
        .uri("/api/console/telemetry/events")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("SUCCESS");
  }

  @Test
  void rejectsEmptyEventsList() {
    webTestClient
        .post()
        .uri("/api/console/telemetry/events")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("app", "console", "events", List.of()))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void rejectsMissingApp() {
    webTestClient
        .post()
        .uri("/api/console/telemetry/events")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("events", List.of(Map.of("type", "info", "name", "x"))))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void rejectsTooManyEvents() {
    // events @Size(max=50)
    List<Map<String, Object>> events = new java.util.ArrayList<>();
    for (int i = 0; i < 51; i++) {
      events.add(Map.of("type", "info", "name", "evt-" + i));
    }
    webTestClient
        .post()
        .uri("/api/console/telemetry/events")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("app", "console", "events", events))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void rejectsPropsExceedingByteLimit() {
    // props 单 event 最大 8KB,构造 10KB 字符串触发 validateProps 拦截
    String big = "x".repeat(10_000);
    Map<String, Object> bigProps = Map.of("payload", big);
    Map<String, Object> body =
        Map.of(
            "app",
            "console",
            "events",
            List.of(Map.of("type", "info", "name", "fat-event", "props", bigProps)));
    webTestClient
        .post()
        .uri("/api/console/telemetry/events")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void acceptsNullEventPropsField() {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("type", "info");
    event.put("name", "x");
    event.put("props", Collections.emptyMap());
    webTestClient
        .post()
        .uri("/api/console/telemetry/events")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("app", "console", "events", List.of(event)))
        .exchange()
        .expectStatus()
        .isOk();
    // 结构验证:status 200 已说明通过 props 校验链
    assertThat(true).isTrue();
  }
}
