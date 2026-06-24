package io.github.pinpols.batch.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.security.RequestSignatures;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 契约一致性：SDK 侧 {@link RequestSigner} 必须与服务端 {@code RequestSignatures} 逐字节相同。
 *
 * <p>两边是独立实现（SDK core 不依赖 batch-common 运行时），靠本测试钉死算法不漂移——任一边改了 canonical 串/HMAC 细节，这里立刻红。
 */
class RequestSignerConformanceTest {

  @Test
  @DisplayName("canonical 串与服务端一致")
  void canonicalMatchesServer() {
    byte[] body = "{\"tenantId\":\"t1\",\"success\":true}".getBytes(StandardCharsets.UTF_8);
    assertThat(
            RequestSigner.canonicalString(
                "POST", "/internal/tasks/10/report", "1700000000000", "n-1", body))
        .isEqualTo(
            RequestSignatures.canonicalString(
                "POST", "/internal/tasks/10/report", "1700000000000", "n-1", body));
  }

  @Test
  @DisplayName("签名与服务端一致(多组输入)")
  void signatureMatchesServer() {
    String[][] cases = {
      {"key-1", "POST", "/internal/tasks/10/report", "1700000000000", "n-1"},
      {"another-key", "post", "/internal/workers/register", "1700000000999", "uuid-abc"},
      {"k", "PUT", "/internal/tasks/leases/renew-batch", "1", "x"},
    };
    byte[][] bodies = {
      "{}".getBytes(StandardCharsets.UTF_8),
      "".getBytes(StandardCharsets.UTF_8),
      "{\"items\":[1,2,3]}".getBytes(StandardCharsets.UTF_8),
    };
    for (int i = 0; i < cases.length; i++) {
      String[] c = cases[i];
      String sdk = RequestSigner.sign(c[0], c[1], c[2], c[3], c[4], bodies[i]);
      String server = RequestSignatures.sign(c[0], c[1], c[2], c[3], c[4], bodies[i]);
      assertThat(sdk).as("case %d", i).isEqualTo(server).matches("[0-9a-f]{64}");
    }
  }

  @Test
  @DisplayName("空 body 摘要与服务端一致")
  void emptyBodyHashMatches() {
    assertThat(RequestSigner.bodySha256Hex(new byte[0]))
        .isEqualTo(RequestSignatures.bodySha256Hex(new byte[0]));
  }
}
