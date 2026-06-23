package io.github.pinpols.batch.worker.exports.stage.format;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * ADR-038 P3 Export GENERATE 续跑:keyset 分页 cursor 的「带类型标签」序列化 codec。
 *
 * <p>cursor 来自 {@code row.get(cursorColumn)},会被原样绑回 SQL {@code WHERE col > :__cursor}。跨崩溃续跑时必须把
 * cursor 落盘成字符串再还原,且<b>必须保住 JDBC 类型</b> —— 例如 timestamp 列若把 cursor 还原成 String,PG 会因 {@code
 * timestamp > text} 无操作符而报错;bigint 列若还原成 BigDecimal 虽可比较但可能丢索引。故 codec 对每种支持的类型打标签, 还原成精确 Java 类型。
 *
 * <p>对无法安全往返的类型(如 UUID:{@code uuid > text} 无操作符)返回 {@link Optional#empty()},调用方据此<b>降级为全量重跑</b>
 * 并打日志(对齐 Import LOAD 续跑「plugin 不声明幂等就拒跑」的前置约束 —— 此处是「cursor 类型不支持就不续跑」)。
 *
 * <p>位点 marker 编码为 {@code "<byteOffset>@<encodedCursor>"}:byteOffset 为纯数字不含 {@code '@'},故按<b>首个</b>
 * {@code '@'} 切分即可,encodedCursor 内部允许包含 {@code '@'} / {@code '|'}(String cursor 值)。
 */
@Component
public class GenerateCursorCodec {

  /** 位点 marker:文件已 fsync 到的字节偏移 + 下一页起始 cursor 的编码。 */
  public record Marker(long byteOffset, String encodedCursor) {}

  /**
   * 把 cursor 编码为 {@code "<TYPE>|<value>"};不支持的类型(含 null)返回 empty。
   *
   * @param cursor keyset 分页 cursor(下一页的起点值)
   */
  public Optional<String> encodeCursor(Object cursor) {
    if (cursor == null) {
      return Optional.empty();
    }
    if (cursor instanceof Long v) {
      return Optional.of("L|" + v);
    }
    if (cursor instanceof Integer || cursor instanceof Short || cursor instanceof Byte) {
      return Optional.of("I|" + ((Number) cursor).intValue());
    }
    if (cursor instanceof BigInteger v) {
      return Optional.of("BI|" + v);
    }
    if (cursor instanceof BigDecimal v) {
      return Optional.of("D|" + v.toPlainString());
    }
    if (cursor instanceof Double || cursor instanceof Float) {
      return Optional.of(
          "D|" + BigDecimal.valueOf(((Number) cursor).doubleValue()).toPlainString());
    }
    if (cursor instanceof Boolean v) {
      return Optional.of("B|" + v);
    }
    if (cursor instanceof Timestamp v) {
      // 纳秒精度经 Instant 往返保真;还原为 java.sql.Timestamp 以匹配 timestamp(tz) 列绑定。
      return Optional.of("TS|" + v.toInstant().toString());
    }
    if (cursor instanceof Instant v) {
      return Optional.of("TS|" + v);
    }
    if (cursor instanceof LocalDateTime v) {
      return Optional.of("LDT|" + v);
    }
    if (cursor instanceof Date v) {
      return Optional.of("DT|" + v); // yyyy-MM-dd
    }
    if (cursor instanceof LocalDate v) {
      return Optional.of("DT|" + v);
    }
    if (cursor instanceof String v) {
      return Optional.of("S|" + v);
    }
    // UUID / 其他:无法安全绑回(uuid > text 无操作符)→ 不支持,调用方降级全量重跑。
    return Optional.empty();
  }

  /**
   * 把 {@link #encodeCursor} 的输出还原为精确 Java 类型。marker 损坏 / 标签未知时抛 {@link
   * IllegalArgumentException},调用方按「位点损坏 → 从头跑」回退。
   */
  public Object decodeCursor(String encoded) {
    if (encoded == null) {
      throw new IllegalArgumentException("null cursor marker");
    }
    int sep = encoded.indexOf('|');
    if (sep < 0) {
      throw new IllegalArgumentException("malformed cursor marker: " + encoded);
    }
    String type = encoded.substring(0, sep);
    String value = encoded.substring(sep + 1);
    return switch (type) {
      case "L" -> Long.valueOf(value);
      case "I" -> Integer.valueOf(value);
      case "BI" -> new BigInteger(value);
      case "D" -> new BigDecimal(value);
      case "B" -> Boolean.valueOf(value);
      case "TS" -> Timestamp.from(Instant.parse(value));
      case "LDT" -> LocalDateTime.parse(value);
      case "DT" -> Date.valueOf(value);
      case "S" -> value;
      default -> throw new IllegalArgumentException("unknown cursor type tag: " + type);
    };
  }

  /** marker = {@code "<byteOffset>@<encodedCursor>"}。 */
  public String encodeMarker(long byteOffset, String encodedCursor) {
    return byteOffset + "@" + encodedCursor;
  }

  /** 解析 marker;首个 {@code '@'} 之前是 byteOffset,之后(可含 {@code '@'})是 encodedCursor。 */
  public Marker decodeMarker(String marker) {
    if (marker == null) {
      throw new IllegalArgumentException("null marker");
    }
    int at = marker.indexOf('@');
    if (at <= 0) {
      throw new IllegalArgumentException("malformed marker: " + marker);
    }
    long offset = Long.parseLong(marker.substring(0, at));
    String encodedCursor = marker.substring(at + 1);
    return new Marker(offset, encodedCursor);
  }
}
