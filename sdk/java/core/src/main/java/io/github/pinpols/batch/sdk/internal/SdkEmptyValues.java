package io.github.pinpols.batch.sdk.internal;

import java.util.Collection;
import java.util.Map;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/**
 * SDK 独立的空或空白值判断。
 *
 * <p>core 不依赖平台 {@code batch-common}，因此保留本地实现；与平台 {@code EmptyValues} 保持语义一致，
 * 差异仅限支持的 API 形状。对象引用判空使用原生 {@code == null}，本类只处理“null 也算空”的集合语义。
 */
public final class SdkEmptyValues {

    private SdkEmptyValues() {}

    @Contract("null -> true")
    public static boolean isEmpty(@Nullable CharSequence value) {
        return value == null || value.length() == 0;
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
    public static boolean isEmpty(@Nullable Map<?, ?> value) {
        return value == null || value.isEmpty();
    }

    public static boolean isNotEmpty(@Nullable Map<?, ?> value) {
        return !isEmpty(value);
    }

    @Contract("null -> true")
    public static boolean isEmpty(@Nullable Collection<?> value) {
        return value == null || value.isEmpty();
    }

    public static boolean isNotEmpty(@Nullable Collection<?> value) {
        return !isEmpty(value);
    }

    @Contract("null -> true")
    public static boolean isEmpty(@Nullable byte[] value) {
        return value == null || value.length == 0;
    }

    public static boolean isNotEmpty(@Nullable byte[] value) {
        return !isEmpty(value);
    }

    @Contract("null -> true")
    public static boolean isEmpty(@Nullable Object[] value) {
        return value == null || value.length == 0;
    }

    public static boolean isNotEmpty(@Nullable Object[] value) {
        return !isEmpty(value);
    }
}
