package io.github.pinpols.batch.testing;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.test.context.DynamicPropertyRegistry;

/** 为 Worker 使用的编排器 HTTP 端点（{@code /internal/workers/**}）提供桩服务， 使集成测试无需启动真实的编排器进程。 */
public final class OrchestratorWireMockSupport {

  private static final AtomicReference<ServerState> STATE = new AtomicReference<>();

  private OrchestratorWireMockSupport() {}

  public static void ensureStarted() {
    if (STATE.get() == null) {
      synchronized (OrchestratorWireMockSupport.class) {
        if (STATE.get() == null) {
          try {
            HttpServer httpServer = HttpServer.create(new InetSocketAddress(0), 0);
            httpServer.createContext("/internal/", exchange -> {
              byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, body.length);
              exchange.getResponseBody().write(body);
              exchange.close();
            });
            httpServer.start();
            STATE.set(new ServerState(
                httpServer, "http://localhost:" + httpServer.getAddress().getPort()));
          } catch (IOException ex) {
            throw new IllegalStateException("Failed to start orchestrator stub server", ex);
          }
          Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ServerState state = STATE.getAndSet(null);
            if (state != null) {
              state.server().stop(0);
            }
          }));
        }
      }
    }
  }

  /** 注册 {@code batch.orchestrator.base-url} 和 {@code batch.worker.task-client.base-url} 属性。 */
  public static void registerOrchestratorBaseUrls(DynamicPropertyRegistry registry) {
    ensureStarted();
    registry.add("batch.orchestrator.base-url", OrchestratorWireMockSupport::baseUrl);
    registry.add("batch.worker.task-client.base-url", OrchestratorWireMockSupport::baseUrl);
  }

  private static String baseUrl() {
    ServerState state = STATE.get();
    if (state == null) {
      throw new IllegalStateException("orchestrator stub server is not started");
    }
    return state.baseUrl();
  }

  private record ServerState(HttpServer server, String baseUrl) {}
}
