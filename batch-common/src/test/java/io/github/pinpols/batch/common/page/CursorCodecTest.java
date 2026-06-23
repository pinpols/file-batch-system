package io.github.pinpols.batch.common.page;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CursorCodecTest {

  @Test
  void encodeThenDecodeRoundTrip() {
    String token = CursorCodec.encode(Map.of("id", 12345L, "createdAt", "2026-05-20T10:00:00Z"));
    assertThat(token).isNotBlank();
    Map<String, Object> decoded = CursorCodec.decode(token);
    assertThat(decoded)
        .containsEntry("id", 12345)
        .containsEntry("createdAt", "2026-05-20T10:00:00Z");
  }

  @Test
  void encodeEmptyReturnsNull() {
    assertThat(CursorCodec.encode(null)).isNull();
    assertThat(CursorCodec.encode(Map.of())).isNull();
  }

  @Test
  void decodeNullOrBlankReturnsEmpty() {
    assertThat(CursorCodec.decode(null)).isEmpty();
    assertThat(CursorCodec.decode("")).isEmpty();
    assertThat(CursorCodec.decode("   ")).isEmpty();
  }

  @Test
  void decodeBrokenTokenReturnsEmptyNotThrow() {
    // 非 base64
    assertThat(CursorCodec.decode("not-valid-base64-!@#$%^")).isEmpty();
    // 合法 base64 但内容不是 JSON 对象
    assertThat(CursorCodec.decode("aGVsbG8")).isEmpty();
    // 合法 base64 但 JSON 数组(不是对象)
    String arrToken =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("[1,2,3]".getBytes());
    assertThat(CursorCodec.decode(arrToken)).isEmpty();
  }

  @Test
  void pageQueryFactoryPreferCursor() {
    PageQuery q = PageQuery.of(5, "tok", 50);
    assertThat(q).isInstanceOf(PageQuery.Cursor.class);
    assertThat(((PageQuery.Cursor) q).token()).isEqualTo("tok");
  }

  @Test
  void pageQueryFactoryFallsBackToOffset() {
    PageQuery q = PageQuery.of(3, null, 20);
    assertThat(q).isInstanceOf(PageQuery.Offset.class);
    PageQuery.Offset o = (PageQuery.Offset) q;
    assertThat(o.pageNo()).isEqualTo(3);
    assertThat(o.offset()).isEqualTo(40L);
  }

  @Test
  void offsetNormalizesPageNoBelowOne() {
    PageQuery.Offset o = new PageQuery.Offset(0, 20);
    assertThat(o.pageNo()).isEqualTo(1);
  }

  @Test
  void pagedResultOffsetFactoryComputesHasMore() {
    PagedResult<String> r = PagedResult.offset(java.util.List.of("a", "b"), 50L, 2, 20);
    assertThat(r.total()).isEqualTo(50L);
    assertThat(r.pageNo()).isEqualTo(2);
    assertThat(r.hasMore()).isTrue(); // 2*20=40 < 50
    assertThat(r.nextCursor()).isNull();
  }

  @Test
  void pagedResultCursorFactory() {
    PagedResult<String> r = PagedResult.cursor(java.util.List.of("a"), "next-tok");
    assertThat(r.nextCursor()).isEqualTo("next-tok");
    assertThat(r.hasMore()).isTrue();
    assertThat(r.total()).isNull();
    assertThat(r.pageNo()).isNull();
  }
}
