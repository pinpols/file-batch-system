package io.github.pinpols.batch.common.page;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("游标编解码:往返一致、空值短路与损坏令牌容错")
class CursorCodecTest {

  @Test
  @DisplayName("编码后再解码得到的键值与原游标一致")
  void shouldRoundTripKeysAndValues_whenEncodingThenDecoding() {
    String token = CursorCodec.encode(Map.of("id", 12345L, "createdAt", "2026-05-20T10:00:00Z"));
    assertThat(token).isNotBlank();
    Map<String, Object> decoded = CursorCodec.decode(token);
    assertThat(decoded)
        .containsEntry("id", 12345)
        .containsEntry("createdAt", "2026-05-20T10:00:00Z");
  }

  @Test
  @DisplayName("空或缺失游标编码结果为空值")
  void shouldReturnNull_whenCursorIsEmpty() {
    assertThat(CursorCodec.encode(null)).isNull();
    assertThat(CursorCodec.encode(Map.of())).isNull();
  }

  @Test
  @DisplayName("令牌缺失或全空白时解码得到空映射")
  void shouldReturnEmptyMap_whenTokenIsNullOrBlank() {
    assertThat(CursorCodec.decode(null)).isEmpty();
    assertThat(CursorCodec.decode("")).isEmpty();
    assertThat(CursorCodec.decode("   ")).isEmpty();
  }

  @Test
  @DisplayName("非法编码或负载不是对象的损坏令牌解码为空映射且不抛异常")
  void shouldReturnEmptyMap_whenTokenIsBroken() {
    // 非 base64
    assertThat(CursorCodec.decode("not-valid-base64-!@#$%^")).isEmpty();
    // 合法 base64 但内容不是 JSON 对象
    assertThat(CursorCodec.decode("aGVsbG8")).isEmpty();
    // 合法 base64 但 JSON 数组(不是对象)
    String arrToken =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("[1,2,3]".getBytes());
    assertThat(CursorCodec.decode(arrToken)).isEmpty();
  }
}
