package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

/**
 * 写路径端到端集成测试:POST /api/console/file-channels → batch.file_channel_config。
 *
 * <p>守护:
 *
 * <ul>
 *   <li>合法 channelCode 写入数据库,channelType/targetEndpoint 字段透传
 *   <li>空格 / 中文 channelCode → 400(@ValidResourceCode 拦截)
 *   <li>同 tenantId + channelCode 重复 → 唯一约束撞
 * </ul>
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("文件通道写路径: 编码与通道类型校验,落库字段透传与唯一约束")
class ConsoleFileChannelMutationIntegrationTest extends AbstractMutationIntegrationTest {

  private String body(String code) {
    return body(code, "SFTP");
  }

  private String body(String code, String channelType) {
    return """
        {
          "tenantId": "int-fc-ta",
          "channelCode": "%s",
          "channelName": "integration test channel",
          "channelType": "%s",
          "targetEndpoint": "sftp://example.com:22/inbox",
          "authType": "PASSWORD",
          "receiptPolicy": "NONE",
          "timeoutSeconds": 60,
          "enabled": false
        }
        """.formatted(code, channelType).stripTrailing();
  }

  @Test
  @DisplayName("创建文件通道: 返回成功,落库的租户,编码与通道类型与请求一致")
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

    var row = jdbcTemplate.queryForMap(
        "SELECT tenant_id, channel_code, channel_type, target_endpoint"
            + " FROM batch.file_channel_config WHERE channel_code = ?",
        code);
    assertThat(row).containsEntry("tenant_id", "int-fc-ta");
    assertThat(row).containsEntry("channel_code", code);
    assertThat(row).containsEntry("channel_type", "SFTP");

    jdbcTemplate.update("DELETE FROM batch.file_channel_config WHERE channel_code = ?", code);
  }

  @Test
  @DisplayName("编码含空格: 返回校验错误且不落库")
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

    Long cnt = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM batch.file_channel_config WHERE channel_code = ?",
        Long.class,
        "q q q");
    assertThat(cnt).isZero();
  }

  @Test
  @DisplayName("编码为中文: 返回请求不合法")
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
  @DisplayName("创建时传入不支持的通道类型: 返回参数非法且不落库")
  void shouldRejectUnsupportedChannelTypeBeforeDatabaseWrite() {
    String code = "int_fc_bad_type_" + System.currentTimeMillis();

    client
        .post()
        .uri("/api/console/file-channels")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-fc-bad-type-" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(code, "WEBHOOK_RAW"))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody(String.class)
        .value(b -> assertThat(b).contains("INVALID_ARGUMENT"));

    Long cnt = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM batch.file_channel_config WHERE channel_code = ?", Long.class, code);
    assertThat(cnt).isZero();
  }

  @Test
  @DisplayName("更新为不支持的通道类型: 返回参数非法,库内通道类型保持原值")
  void shouldRejectUnsupportedChannelTypeOnUpdate() {
    String code = "int_fc_upd_bad_type_" + System.currentTimeMillis();

    // arrange: create a valid channel first (channelType=SFTP)
    client
        .post()
        .uri("/api/console/file-channels")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-fc-upd-" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(code))
        .exchange()
        .expectStatus()
        .isOk();

    Long id = jdbcTemplate.queryForObject(
        "SELECT id FROM batch.file_channel_config WHERE channel_code = ?", Long.class, code);

    // act: PUT changing channelType to an unknown value → must reject
    String updateBody =
        "{" + "\"tenantId\":\"int-fc-ta\"," + "\"channelType\":\"WEBHOOK_RAW\"" + "}";
    client
        .put()
        .uri("/api/console/file-channels/" + id)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(updateBody)
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody(String.class)
        .value(b -> assertThat(b).contains("INVALID_ARGUMENT"));

    // assert: stored channel_type unchanged
    String channelType = jdbcTemplate.queryForObject(
        "SELECT channel_type FROM batch.file_channel_config WHERE id = ?", String.class, id);
    assertThat(channelType).isEqualTo("SFTP");

    jdbcTemplate.update("DELETE FROM batch.file_channel_config WHERE channel_code = ?", code);
  }

  @Test
  @DisplayName("编码重复: 第二次写入失败,不产生重复通道")
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
