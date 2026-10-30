package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.worker.dispatchs.config.DispatchRuntimeProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("HTTP 渠道健康探针不把 URL 凭据写入成功或失败证据")
class RemoteFilesystemHttpProbeSecurityTest {

  @Test
  @DisplayName("连接异常中的凭据不会进入探针失败说明")
  void shouldRedactCredentialsFromProbeFailureMessage() {
    OkHttpClient client = new OkHttpClient.Builder()
        .addInterceptor(chain -> {
          throw new IOException(
              "password=probe-secret-marker at http://user:probe-url-marker@example.test/path");
        })
        .build();

    DispatchChannelProbeResult result = RemoteFilesystemDispatchSupport.probeHttp(
        Map.of("target_endpoint", "http://127.0.0.1/probe"),
        false,
        new DispatchRuntimeProperties(),
        client);

    assertThat(result.success()).isFalse();
    assertThat(result.message()).doesNotContain("probe-secret-marker", "probe-url-marker");
  }

  @ParameterizedTest
  @ValueSource(ints = {204, 503})
  @DisplayName("探针回执只保留脱敏端点")
  void shouldRedactCredentialsFromProbeEvidence(int status) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/probe", exchange -> {
      exchange.sendResponseHeaders(status, -1);
      exchange.close();
    });
    server.start();
    try {
      String endpoint = "http://user:probe-url-marker@127.0.0.1:"
          + server.getAddress().getPort()
          + "/probe?api_key=probe-query-marker";

      DispatchChannelProbeResult result = RemoteFilesystemDispatchSupport.probeHttp(
          Map.of("target_endpoint", endpoint),
          false,
          new DispatchRuntimeProperties(),
          new OkHttpClient());

      assertThat(result.success()).isEqualTo(status == 204);
      assertThat(result.evidenceRef()).contains("127.0.0.1", "****");
      assertThat(result.evidenceRef()).doesNotContain("probe-url-marker", "probe-query-marker");
    } finally {
      server.stop(0);
    }
  }
}
