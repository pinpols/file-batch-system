package com.example.batch.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link PlatformHttpClient} 真 HTTP 用 JDK {@link HttpServer} 起 stub server。 */
class PlatformHttpClientTest {

  private HttpServer server;
  private int port;

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    port = server.getAddress().getPort();
    server.start();
  }

  @AfterEach
  void tearDown() {
    if (server != null) server.stop(0);
  }

  private PlatformHttpClient newClient() {
    return new PlatformHttpClient(
        BatchPlatformClientConfig.builder()
            .baseUrl("http://127.0.0.1:" + port)
            .apiKey("test-key")
            .tenantId("tx")
            .workerCode("w-1")
            .kafkaBootstrap("kafka:9092")
            .kafkaTopicPattern("p.*")
            .kafkaGroupId("g")
            .httpTimeout(Duration.ofSeconds(2))
            .build());
  }

  @Test
  void registerReturnsResponse() throws IOException {
    AtomicReference<String> seenAuth = new AtomicReference<>();
    AtomicReference<String> seenTenant = new AtomicReference<>();
    server.createContext(
        "/api/internal/workers/register",
        ex -> {
          seenAuth.set(ex.getRequestHeaders().getFirst("X-Batch-Api-Key"));
          seenTenant.set(ex.getRequestHeaders().getFirst("X-Batch-Tenant-Id"));
          byte[] body = "{\"workerId\":\"assigned-123\"}".getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(200, body.length);
          ex.getResponseBody().write(body);
          ex.close();
        });

    Map<String, Object> resp = newClient().register(Map.of("workerCode", "w-1"));

    assertThat(resp).containsEntry("workerId", "assigned-123");
    assertThat(seenAuth.get()).isEqualTo("test-key");
    assertThat(seenTenant.get()).isEqualTo("tx");
  }

  @Test
  void claimIncludesIdempotencyKey() throws IOException {
    AtomicReference<String> seenIdem = new AtomicReference<>();
    server.createContext(
        "/api/internal/tasks/42/claim",
        ex -> {
          seenIdem.set(ex.getRequestHeaders().getFirst("Idempotency-Key"));
          ex.sendResponseHeaders(200, -1);
          ex.close();
        });

    newClient().claim(42L, "idem-xyz", Map.of("workerCode", "w-1"));

    assertThat(seenIdem.get()).isEqualTo("idem-xyz");
  }

  @Test
  void non2xxThrows() {
    server.createContext(
        "/api/internal/workers/heartbeat",
        ex -> {
          byte[] body = "{\"code\":\"FORBIDDEN\"}".getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(403, body.length);
          ex.getResponseBody().write(body);
          ex.close();
        });

    assertThatThrownBy(() -> newClient().heartbeat(Map.of()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("HTTP 403")
        .hasMessageContaining("FORBIDDEN");
  }

  @Test
  void reportEmptyResponseOk() throws IOException {
    server.createContext(
        "/api/internal/tasks/99/report",
        ex -> {
          ex.sendResponseHeaders(204, -1);
          ex.close();
        });
    Map<String, Object> r = newClient().report(99L, "idem", Map.of("success", true));
    assertThat(r).isEmpty();
  }
}
