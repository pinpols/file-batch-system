package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.console.BatchConsoleApiApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

/**
 * 写路径端到端集成测试:POST /api/console/file-channels → batch.file_channel_config。
 *
 * <p>守护:
 *
 * <ul>
 *   <li>合法 channelCode 落库,channelType/targetEndpoint 字段透传
 *   <li>空格 / 中文 channelCode → 400(@ValidResourceCode 拦截)
 *   <li>同 tenantId + channelCode 重复 → 唯一约束撞
 * </ul>
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.security.bypass-mode=true", "batch.console.ai.enabled=false"})
class ConsoleFileChannelMutationIntegrationTest extends AbstractMutationIntegrationTest {

  private String body(String code) {
    return "{"
        + "\"tenantId\":\"int-fc-ta\","
        + "\"channelCode\":\""
        + code
        + "\","
        + "\"channelName\":\"integration test channel\","
        + "\"channelType\":\"SFTP\","
        + "\"targetEndpoint\":\"sftp://example.com:22/inbox\","
        + "\"authType\":\"PASSWORD\","
        + "\"receiptPolicy\":\"NONE\","
        + "\"timeoutSeconds\":60,"
        + "\"enabled\":false"
        + "}";
  }

  @Test
  void shouldCreateFileChannelWithValidCode() {
    String code = "int_fc_create_" + System.currentTimeMillis();

    client
        .post()
        .uri("/api/console/file-channels")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-fc-" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(code))
        .exchange()
        .expectStatus()
        .isOk();

    var row =
        jdbcTemplate.queryForMap(
            "SELECT tenant_id, channel_code, channel_type, target_endpoint"
                + " FROM batch.file_channel_config WHERE channel_code = ?",
            code);
    assertThat(row.get("tenant_id")).isEqualTo("int-fc-ta");
    assertThat(row.get("channel_code")).isEqualTo(code);
    assertThat(row.get("channel_type")).isEqualTo("SFTP");

    jdbcTemplate.update("DELETE FROM batch.file_channel_config WHERE channel_code = ?", code);
  }

  @Test
  void shouldRejectInvalidChannelCodeWithSpaces() {
    client
        .post()
        .uri("/api/console/file-channels")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-fc-bad-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body("q q q"))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody(String.class)
        .value(b -> assertThat(b).contains("VALIDATION_ERROR"));

    Long cnt =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM batch.file_channel_config WHERE channel_code = ?",
            Long.class,
            "q q q");
    assertThat(cnt).isZero();
  }

  @Test
  void shouldRejectChineseChannelCode() {
    client
        .post()
        .uri("/api/console/file-channels")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-fc-bad-2")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body("中文渠道"))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void shouldRejectDuplicateChannelCode() {
    String code = "int_fc_dup_" + System.currentTimeMillis();
    client
        .post()
        .uri("/api/console/file-channels")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-fc-dup-1-" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(code))
        .exchange()
        .expectStatus()
        .isOk();

    client
        .post()
        .uri("/api/console/file-channels")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-fc-dup-2-" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(code))
        .exchange()
        .expectStatus()
        .value(s -> assertThat(s).isIn(400, 409, 500));

    jdbcTemplate.update("DELETE FROM batch.file_channel_config WHERE channel_code = ?", code);
  }
}
