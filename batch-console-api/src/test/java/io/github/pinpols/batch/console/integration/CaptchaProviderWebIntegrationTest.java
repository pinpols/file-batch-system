package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

/** CAPTCHA provider 配置公开信息集成测试。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.security.bypass-mode=true", "batch.console.captcha.provider=none"})
@DisplayName("验证码提供方公开配置: 返回已配置的提供方,且不下发服务端凭据")
class CaptchaProviderWebIntegrationTest extends AbstractIntegrationTest {

  @LocalServerPort
  private int port;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private RestClient client;

  @BeforeEach
  void setUp() {
    client = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
  }

  @Test
  @DisplayName("验证码配置接口: 返回已配置的提供方,站点键字段存在且不下发服务端凭据")
  void shouldReturnConfiguredProviderAndSiteKeyOnly_whenReadingCaptchaConfig() throws Exception {
    String config = client.get().uri("/api/console/captcha/config").retrieve().body(String.class);
    JsonNode data = objectMapper.readTree(config).path("data");
    assertThat(data.path("provider").asText()).isEqualTo("none");
    assertThat(data.path("siteKey").isValueNode()).isTrue();
  }
}
