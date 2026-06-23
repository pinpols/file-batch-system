package io.github.pinpols.batch.testing;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.springframework.test.context.DynamicPropertyRegistry;

/** 为 Worker 使用的编排器 HTTP 端点（{@code /internal/workers/**}）提供桩服务， 使集成测试无需启动真实的编排器进程。 */
public final class OrchestratorWireMockSupport {

  private static volatile HttpServer server;
  private static volatile String baseUrl;

  private OrchestratorWireMockSupport() {}

  public static void ensureStarted() {
    if (server == null) {
      synchronized (OrchestratorWireMockSupport.class) {
        if (server == null) {
          try {
            HttpServer httpServer = HttpServer.create(new InetSocketAddress(0), 0);
            httpServer.createContext(
                "/internal/",
                exchange -> {
                  byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                  exchange.getResponseHeaders().set("Content-Type", "application/json");
                  exchange.sendResponseHeaders(200, body.length);
                  exchange.getResponseBody().write(body);
                  exchange.close();
                });
            httpServer.start();
            server = httpServer;
            baseUrl = "http://localhost:" + httpServer.getAddress().getPort();
          } catch (IOException ex) {
            throw new IllegalStateException("Failed to start orchestrator stub server", ex);
          }
          Runtime.getRuntime()
              .addShutdownHook(
                  new Thread(
                      () -> {
                        if (server != null) {
                          server.stop(0);
                          server = null;
                          baseUrl = null;
                        }
                      }));
        }
      }
    }
  }

  /** 注册 {@code batch.orchestrator.base-url} 和 {@code batch.worker.task-client.base-url} 属性。 */
  public static void registerOrchestratorBaseUrls(DynamicPropertyRegistry registry) {
    ensureStarted();
    registry.add("batch.orchestrator.base-url", () -> baseUrl);
    registry.add("batch.worker.task-client.base-url", () -> baseUrl);
  }
}
