package io.github.pinpols.batch.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 请求签名契约：canonical 串 + HMAC-SHA256，跨 5 语言 SDK 与服务端验签的唯一权威源。 */
class RequestSignaturesTest {

  private static final byte[] EMPTY = new byte[0];

  @Test
  @DisplayName("空 body 的 sha256 hex 等于公认常量")
  void emptyBodyHashIsKnownConstant() {
    assertThat(RequestSignatures.bodySha256Hex(EMPTY))
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  }

  @Test
  @DisplayName("canonical 串按 method/path/ts/nonce/bodyHash 用 \\n 连接")
  void canonicalStringJoinsFieldsWithNewline() {
    String c =
        RequestSignatures.canonicalString(
            "post", "/internal/tasks/10/claim", "1700000000000", "abc123", EMPTY);
    assertThat(c)
        .isEqualTo(
            "POST\n/internal/tasks/10/claim\n1700000000000\nabc123\n"
                + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  }

  @Test
  @DisplayName("sign 产出 64 位小写 hex，且确定性")
  void signIsLowercaseHexAndDeterministic() {
    String s1 =
        RequestSignatures.sign(
            "key-1", "POST", "/p", "1", "n", "{}".getBytes(StandardCharsets.UTF_8));
    String s2 =
        RequestSignatures.sign(
            "key-1", "POST", "/p", "1", "n", "{}".getBytes(StandardCharsets.UTF_8));
    assertThat(s1).hasSize(64).isEqualTo(s2).matches("[0-9a-f]{64}");
  }

  @Test
  @DisplayName("任一字段变化签名即变（nonce/key/body）")
  void anyFieldChangeAltersSignature() {
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    String base = RequestSignatures.sign("k", "POST", "/p", "1", "n", body);
    assertThat(RequestSignatures.sign("k", "POST", "/p", "1", "n2", body)).isNotEqualTo(base);
    assertThat(RequestSignatures.sign("k2", "POST", "/p", "1", "n", body)).isNotEqualTo(base);
    assertThat(
            RequestSignatures.sign(
                "k", "POST", "/p", "1", "n", "{ }".getBytes(StandardCharsets.UTF_8)))
        .isNotEqualTo(base);
  }
}
