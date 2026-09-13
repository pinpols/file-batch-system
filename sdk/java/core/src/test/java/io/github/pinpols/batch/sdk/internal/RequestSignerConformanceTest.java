package io.github.pinpols.batch.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 契约一致性：SDK 侧 {@link RequestSigner} 必须与服务端签名规范逐字节相同。
 *
 * <p>SDK core 不依赖 batch-common；这里用固定测试向量钉死 canonical 串、body hash 和 HMAC 输出。
 */
class RequestSignerConformanceTest {

  @Test
  @DisplayName("canonical 串与服务端一致")
  void canonicalMatchesServer() {
    byte[] body = "{\"tenantId\":\"t1\",\"success\":true}".getBytes(StandardCharsets.UTF_8);
    assertThat(RequestSigner.canonicalString(
            "POST", "/internal/tasks/10/report", "1700000000000", "n-1", body))
        .isEqualTo("POST\n"
            + "/internal/tasks/10/report\n"
            + "1700000000000\n"
            + "n-1\n"
            + "c9a04b2061b2c381193ee868b9d89bc16979c738d257f8495d18457a83462dd5");
  }

  @Test
  @DisplayName("签名与服务端一致(多组输入)")
  void signatureMatchesServer() {
    String[][] cases = {
      {
        "key-1",
        "POST",
        "/internal/tasks/10/report",
        "1700000000000",
        "n-1",
        "2192cde67d54f510cc69037958816586f5253dff4f3a591763ed331eb0d58dc5"
      },
      {
        "another-key",
        "post",
        "/internal/workers/register",
        "1700000000999",
        "uuid-abc",
        "1fbcc352070fd6e0d270a5b1b912f5b152a4e55a15c2a5c55e0c3e1202a1c92e"
      },
      {
        "k",
        "PUT",
        "/internal/tasks/leases/renew-batch",
        "1",
        "x",
        "b37ffd4fb33e83f5723e94e7bf06bebdf7da013eed92c3e7c0e9d8d973448acf"
      },
    };
    byte[][] bodies = {
      "{}".getBytes(StandardCharsets.UTF_8),
      "".getBytes(StandardCharsets.UTF_8),
      "{\"items\":[1,2,3]}".getBytes(StandardCharsets.UTF_8),
    };
    for (int i = 0; i < cases.length; i++) {
      String[] c = cases[i];
      String sdk = RequestSigner.sign(c[0], c[1], c[2], c[3], c[4], bodies[i]);
      assertThat(sdk).as("case %d", i).isEqualTo(c[5]).matches("[0-9a-f]{64}");
    }
  }

  @Test
  @DisplayName("空 body 摘要与服务端一致")
  void emptyBodyHashMatches() {
    assertThat(RequestSigner.bodySha256Hex(new byte[0]))
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  }
}
