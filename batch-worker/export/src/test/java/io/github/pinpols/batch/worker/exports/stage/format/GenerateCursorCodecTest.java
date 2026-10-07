package io.github.pinpols.batch.worker.exports.stage.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-038 P3:cursor codec 必须保住 JDBC 类型往返,否则续跑绑回 SQL 会类型不符。 */
@DisplayName("生成阶段游标编解码单测:各数据库类型的往返保真与畸形输入拒绝")
class GenerateCursorCodecTest {

  private final GenerateCursorCodec codec = new GenerateCursorCodec();

  @Test
  @DisplayName("长整型游标编解码后仍是长整型,数值不变")
  void roundTrip_preservesLongType() {
    String encoded = codec.encodeCursor(123_456_789_012L).orElseThrow();
    Object decoded = codec.decodeCursor(encoded);
    assertThat(decoded).isInstanceOf(Long.class).isEqualTo(123_456_789_012L);
  }

  @Test
  @DisplayName("整型游标往返后保持整型类型与取值")
  void roundTrip_preservesIntegerType() {
    Object decoded = codec.decodeCursor(codec.encodeCursor(42).orElseThrow());
    assertThat(decoded).isInstanceOf(Integer.class).isEqualTo(42);
  }

  @Test
  @DisplayName("小数游标往返后按数值相等比较一致,精度不丢")
  void roundTrip_preservesBigDecimalScale() {
    BigDecimal cursor = new BigDecimal("100.5000");
    Object decoded = codec.decodeCursor(codec.encodeCursor(cursor).orElseThrow());
    assertThat(decoded).isInstanceOf(BigDecimal.class);
    assertThat(((BigDecimal) decoded)).isEqualByComparingTo(cursor);
  }

  @Test
  @DisplayName("大整数游标往返后保持大整数类型与取值")
  void roundTrip_preservesBigInteger() {
    Object decoded =
        codec.decodeCursor(codec.encodeCursor(new BigInteger("99999999999999")).orElseThrow());
    assertThat(decoded).isInstanceOf(BigInteger.class).isEqualTo(new BigInteger("99999999999999"));
  }

  @Test
  @DisplayName("布尔游标往返后取值不变")
  void roundTrip_preservesBoolean() {
    assertThat(codec.decodeCursor(codec.encodeCursor(true).orElseThrow())).isEqualTo(true);
  }

  @Test
  @DisplayName("带纳秒精度的时间戳游标往返后完全一致")
  void roundTrip_preservesTimestampNanos() {
    Timestamp cursor = Timestamp.from(Instant.parse("2026-06-05T01:02:03.123456789Z"));
    Object decoded = codec.decodeCursor(codec.encodeCursor(cursor).orElseThrow());
    assertThat(decoded).isInstanceOf(Timestamp.class).isEqualTo(cursor);
  }

  @Test
  @DisplayName("日期游标往返后仍按同一日期字符串输出")
  void roundTrip_preservesSqlDate() {
    Date cursor = Date.valueOf("2026-06-05");
    Object decoded = codec.decodeCursor(codec.encodeCursor(cursor).orElseThrow());
    assertThat(decoded).isInstanceOf(Date.class).hasToString("2026-06-05");
  }

  @Test
  @DisplayName("字符串游标含分隔符时仍整体保真,切分只按首个分隔符")
  void roundTrip_preservesStringContainingSeparatorChars() {
    // String cursor 值可能含 '|' 与 '@' —— 解码须按首个 '|' 切类型、按首个 '@' 切 offset,值整体保真。
    String cursor = "abc|def@ghi";
    Object decoded = codec.decodeCursor(codec.encodeCursor(cursor).orElseThrow());
    assertThat(decoded).isInstanceOf(String.class).isEqualTo(cursor);
  }

  @Test
  @DisplayName("不支持的类型与空值编码结果为空,交由调用方降级处理")
  void encode_returnsEmptyForUnsupportedType() {
    // UUID 绑回 uuid 列无 uuid>text 操作符 → 不支持 → 调用方降级全量重跑。
    assertThat(codec.encodeCursor(UUID.randomUUID())).isEmpty();
    assertThat(codec.encodeCursor(null)).isEmpty();
    assertThat(codec.encodeCursor(new Object())).isEmpty();
  }

  @Test
  @DisplayName("位点标记能同时还原字节偏移与游标,游标内含分隔符也不受影响")
  void marker_roundTripsOffsetAndCursorEvenWhenCursorContainsAt() {
    String encodedCursor = codec.encodeCursor("v@l|ue").orElseThrow();
    String marker = codec.encodeMarker(98_765L, encodedCursor);
    GenerateCursorCodec.Marker parsed = codec.decodeMarker(marker);
    assertThat(parsed.byteOffset()).isEqualTo(98_765L);
    assertThat(parsed.encodedCursor()).isEqualTo(encodedCursor);
    assertThat(codec.decodeCursor(parsed.encodedCursor())).isEqualTo("v@l|ue");
  }

  @Test
  @DisplayName("缺少分隔符或类型标签未知时,解码直接抛参数非法")
  void decodeCursor_throwsOnCorruptMarker() {
    assertThatThrownBy(() -> codec.decodeCursor("no-separator"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> codec.decodeCursor("Z|unknown-tag"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("位点标记缺少偏移部分时,解码抛参数非法")
  void decodeMarker_throwsOnMalformed() {
    assertThatThrownBy(() -> codec.decodeMarker("@onlycursor"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
