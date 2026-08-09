package io.github.pinpols.batch.common.utils;

import java.util.Collection;
import java.util.Map;
import javax.annotation.Nullable;
import org.jetbrains.annotations.Contract;

/**
 * 统一的空值判断工具。
 *
 * <p>对象空值、字符串空白和集合空值是三种不同语义，不能用同一个表达式混用：
 *
 * <ul>
 *   <li>{@link #isNull(Object)} 只判断对象引用是否为 {@code null}；
 *   <li>{@link #isEmpty(CharSequence)} 判断字符串为 {@code null} 或长度为 0，不把空白字符串当作空；
 *   <li>{@link #isBlank(CharSequence)} 判断字符串为 {@code null}、空或全空白；
 *   <li>{@link #isEmpty(Collection)} / {@link #isEmpty(Map)} 判断集合或 Map 为 {@code null} 或无元素。
 * </ul>
 */
public final class EmptyChecks {

  private EmptyChecks() {}

  @Contract("null -> true")
  public static boolean isNull(@Nullable Object value) {
    return value == null;
  }

  @Contract("null -> false")
  public static boolean isNotNull(@Nullable Object value) {
    return value != null;
  }

  @Contract("null -> true")
  public static boolean isEmpty(@Nullable CharSequence value) {
    return value == null || value.isEmpty();
  }

  public static boolean isNotEmpty(CharSequence value) {
    return !isEmpty(value);
  }

  @Contract("null -> true")
  public static boolean isBlank(@Nullable CharSequence value) {
    return !Texts.hasText(value);
  }

  @Contract("null -> false")
  public static boolean isNotBlank(@Nullable CharSequence value) {
    return !isBlank(value);
  }

  @Contract("null -> true")
  public static boolean isEmpty(@Nullable Collection<?> value) {
    return value == null || value.isEmpty();
  }

  public static boolean isNotEmpty(Collection<?> value) {
    return !isEmpty(value);
  }

  @Contract("null -> true")
  public static boolean isEmpty(@Nullable Map<?, ?> value) {
    return value == null || value.isEmpty();
  }

  public static boolean isNotEmpty(Map<?, ?> value) {
    return !isEmpty(value);
  }

  @Contract("null -> true")
  public static boolean isEmpty(@Nullable Object[] value) {
    return value == null || value.length == 0;
  }

  public static boolean isNotEmpty(Object[] value) {
    return !isEmpty(value);
  }
}
