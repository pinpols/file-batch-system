package io.github.pinpols.batch.common.utils;

import java.util.Collection;
import java.util.Map;
import javax.annotation.Nullable;
import org.jetbrains.annotations.Contract;

/**
 * 空或空白值判断。
 *
 * <p>对象引用判空使用原生 {@code x == null} / {@code x != null}；本类只固定“null 也算空”的集合语义：
 *
 * <ul>
 *   <li>{@link #isEmpty(CharSequence)} 判断字符串为 {@code null} 或长度为 0，不把空白字符串当作空；
 *   <li>{@link #isBlank(CharSequence)} 判断字符串为 {@code null}、空或全空白；
 *   <li>{@link #hasText(CharSequence)} 是 {@code isBlank} 的反向语义；
 *   <li>{@link #isEmpty(Collection)} / {@link #isEmpty(Map)} / {@link #isEmpty(Object[])} 判断集合、Map 或数组为
 *       {@code null} 或无元素。
 * </ul>
 *
 * <p>不要为本类新增对象判空包装方法；不要为简单 null 判断抽取工具入口。
 */
@SuppressWarnings("java:S2583")
public final class EmptyValues {

    private EmptyValues() {}

    @Contract("null -> true")
    public static boolean isEmpty(@Nullable CharSequence value) {
        return value == null || value.isEmpty();
    }

    public static boolean isNotEmpty(@Nullable CharSequence value) {
        return !isEmpty(value);
    }

    @Contract("null -> false")
    public static boolean hasText(@Nullable CharSequence str) {
        if (str == null) {
            return false;
        }
        int len = str.length();
        if (len == 0) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (!Character.isWhitespace(str.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** String 重载，避免调用方因 {@code null} 字面量歧义触发 {@code CharSequence} 路径的 NPE。 */
    @Contract("null -> false")
    public static boolean hasText(@Nullable String str) {
        return hasText((CharSequence) str);
    }

    @Contract("null -> true")
    public static boolean isBlank(@Nullable CharSequence value) {
        return !hasText(value);
    }

    @Contract("null -> false")
    public static boolean isNotBlank(@Nullable CharSequence value) {
        return !isBlank(value);
    }

    @Contract("null -> true")
    public static boolean isEmpty(@Nullable Collection<?> value) {
        return value == null || value.isEmpty();
    }

    public static boolean isNotEmpty(@Nullable Collection<?> value) {
        return !isEmpty(value);
    }

    @Contract("null -> true")
    public static boolean isEmpty(@Nullable Map<?, ?> value) {
        return value == null || value.isEmpty();
    }

    public static boolean isNotEmpty(@Nullable Map<?, ?> value) {
        return !isEmpty(value);
    }

    @Contract("null -> true")
    public static boolean isEmpty(@Nullable Object[] value) {
        return value == null || value.length == 0;
    }

    @Contract("null -> true")
    public static boolean isEmpty(@Nullable byte[] value) {
        return value == null || value.length == 0;
    }

    public static boolean isNotEmpty(@Nullable byte[] value) {
        return !isEmpty(value);
    }

    public static boolean isNotEmpty(@Nullable Object[] value) {
        return !isEmpty(value);
    }
}
