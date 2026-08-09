package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.security.RequestSignatures;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

/**
 * {@code batch.request-signing.enabled} 开关集成测试：真实 HTTP 过滤器对携带 api_key 的写请求强制验签，
 * 未签名 / 篡改 401，合法签名放行到路由层。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.security.bypass-mode=true", "batch.request-signing.enabled=true"})
class RequestSigningWebIntegrationTest extends AbstractIntegrationTest {

  private static final String API_KEY = "it-signing-key";
  private static final String PATH = "/internal/tasks/it-nonexistent/claim";
  private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);

  @LocalServerPort
  private int port;

  private RestClient client;

  @BeforeEach
  void setUp() {
    client = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
  }

  @Test
  void unsignedWriteRequestWithApiKeyIsRejected() {
    Response resp = post(null, null, null);
    assertThat(resp.status()).isEqualTo(401);
    assertThat(resp.body()).contains("SIGNATURE_INVALID");
  }

  @Test
  void tamperedSignatureIsRejected() {
    Response resp = post(timestamp(), UUID.randomUUID().toString(), "tampered");
    assertThat(resp.status()).isEqualTo(401);
    assertThat(resp.body()).contains("SIGNATURE_INVALID");
  }

  @Test
  void validSignaturePassesSignatureFilter() {
    String timestamp = timestamp();
    String nonce = UUID.randomUUID().toString();
    String signature = RequestSignatures.sign(API_KEY, "POST", PATH, timestamp, nonce, BODY);
    Response resp = post(timestamp, nonce, signature);

    assertThat(resp.status())
        .as("valid signature must pass the filter (routing/business status is fine)")
        .isNotEqualTo(401);
    assertThat(resp.body()).doesNotContain("SIGNATURE_INVALID");
  }

  private Response post(String timestamp, String nonce, String signature) {
    return client
        .post()
        .uri(PATH)
        .contentType(MediaType.APPLICATION_JSON)
        .headers(headers -> {
          headers.set("X-Batch-Api-Key", API_KEY);
          if (timestamp != null) {
            headers.set("X-Batch-Timestamp", timestamp);
            headers.set("X-Batch-Nonce", nonce);
            headers.set("X-Batch-Signature", signature);
          }
        })
        .body(BODY)
        .exchange((request, response) -> toResponse(response));
  }

  private static Response toResponse(ClientHttpResponse response) throws IOException {
    try (InputStream in = response.getBody()) {
      String body = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
      return new Response(response.getStatusCode().value(), body);
    }
  }

  private static String timestamp() {
    return String.valueOf(System.currentTimeMillis());
  }

  private record Response(int status, String body) {}
}
